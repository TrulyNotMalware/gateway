package dev.notypie.gateway

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

// Top level: the @SpringBootTest properties below cannot see companion members.
private const val ISSUER = "test-issuer"
private const val AUDIENCE = "test-audience"
private const val IP_MAX_REQUESTS = 6
private const val CORS = "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**]"
private const val ALLOWED_ORIGIN = "https://app.example"

/**
 * The one spec that boots the application: the real WebFilter + GlobalFilter chain, routing to a
 * stub backend on a real socket.
 *
 * Unit specs construct each filter alone and stub the rest of the chain, so they cannot see how
 * filters interact with the gateway's own routing and response writing. The defects pinned here
 * were only visible through the real chain (a slow backend answered with an empty 200).
 * Redis stays in its default in-memory mode, so no external service is needed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // The prod value: the deadline for the blacklist / rate-limit checks.
        "app.config.security.timeout-ms=1000",
        // Small enough that counting a request twice is visible. Each case sends from its own
        // client IP (X-Forwarded-For; the resolver trusts the rightmost entry), so cases do not
        // share this budget.
        "app.config.security.ip-max-requests=$IP_MAX_REQUESTS",
        "app.config.security.login-max-requests=1",
        "$CORS.allowed-origins=$ALLOWED_ORIGIN",
        "$CORS.allowed-methods=GET,POST",
        "$CORS.allowed-headers=Authorization,Content-Type",
        "$CORS.allow-credentials=true",
    ],
)
class GatewayApplicationSpec : BehaviorSpec() {
    @LocalServerPort
    private var port: Int = 0

    private fun client() =
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(15))
            .build()

    init {
        extensions(SpringExtension())
        afterSpec { backend.disposeNow() }

        given("the gateway in front of a stub backend") {
            `when`("the backend answers later than security.timeout-ms") {
                val result =
                    client()
                        .get()
                        .uri("/v1/posts/slow")
                        .header("X-Forwarded-For", "10.20.0.1")
                        .exchange()
                        .returnResult(String::class.java)
                val body =
                    result.responseBody
                        .collectList()
                        .awaitSingle()
                        .joinToString("")
                then("the client gets the backend's full response — the deadline bounds only the checks") {
                    result.status shouldBe HttpStatus.OK
                    body shouldBe SLOW_BODY
                    slowCompleted.get() shouldBe 1
                }
            }

            `when`("an authenticated client sends ip-max-requests requests") {
                val token = signedToken(subject = "user-1")
                val responses =
                    (1..IP_MAX_REQUESTS).map {
                        val result =
                            client()
                                .get()
                                .uri("/v1/me")
                                .header("Authorization", "Bearer $token")
                                .header("X-Forwarded-For", "10.20.0.2")
                                .exchange()
                                .returnResult(String::class.java)
                        result.status to
                            result.responseBody
                                .collectList()
                                .awaitSingle()
                                .joinToString("")
                    }
                then("every request passes once through the chain, carrying the verified X-User-ID") {
                    // Counted twice per request, the IP budget would run out halfway.
                    responses.map { it.first } shouldBe List(IP_MAX_REQUESTS) { HttpStatus.OK }
                    responses.last().second shouldBe "user-1"
                }
            }

            `when`("a second login attempt arrives with a percent-encoded path") {
                fun login(path: String) =
                    client()
                        .post()
                        .uri(URI.create("http://localhost:$port$path"))
                        .header("X-Forwarded-For", "10.20.0.3")
                        .exchange()
                        .returnResult(String::class.java)
                        .status
                val first = login("/v1/auth/login")
                val encoded = login("/v1/auth/%6Cogin")
                then("Spring Security lets it through as the login route, and the login limit stops it") {
                    first shouldBe HttpStatus.OK
                    encoded shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }
        }

        given("a browser client on an allowed origin") {
            `when`("Spring Security rejects its token") {
                val result =
                    client()
                        .get()
                        .uri("/v1/me")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer not-a-jwt")
                        .exchange()
                        .returnResult(String::class.java)
                then("the 401 carries CORS headers, so the client can read it") {
                    result.status shouldBe HttpStatus.UNAUTHORIZED
                    result.responseHeaders.accessControlAllowOrigin shouldBe ALLOWED_ORIGIN
                    result.responseHeaders.accessControlAllowCredentials shouldBe true
                }
            }

            `when`("it sends a preflight") {
                val result =
                    client()
                        .options()
                        .uri("/v1/me")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization")
                        .exchange()
                        .returnResult(String::class.java)
                then("it is answered with the configured CORS policy") {
                    result.status shouldBe HttpStatus.OK
                    result.responseHeaders.accessControlAllowOrigin shouldBe ALLOWED_ORIGIN
                }
            }

            `when`("an authenticated request is routed to the backend") {
                val result =
                    client()
                        .get()
                        .uri("/v1/me")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer ${signedToken(subject = "user-2")}")
                        .header("X-Forwarded-For", "10.20.0.4")
                        .exchange()
                        .returnResult(String::class.java)
                then("the response carries Access-Control-Allow-Origin exactly once") {
                    result.status shouldBe HttpStatus.OK
                    result.responseHeaders[HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN] shouldBe listOf(ALLOWED_ORIGIN)
                }
            }
        }

        given("a browser client on an origin that is not allowed") {
            `when`("it sends a request") {
                val result =
                    client()
                        .get()
                        .uri("/v1/me")
                        .header("Origin", "https://evil.example")
                        .header("Authorization", "Bearer ${signedToken(subject = "user-3")}")
                        .header("X-Forwarded-For", "10.20.0.5")
                        .exchange()
                        .returnResult(String::class.java)
                then("it is still rejected, without CORS headers") {
                    result.status shouldBe HttpStatus.FORBIDDEN
                    result.responseHeaders.accessControlAllowOrigin shouldBe null
                }
            }
        }
    }

    companion object {
        private const val SLOW_BODY = "slow-body"

        private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

        private fun signedToken(subject: String): String =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.keyID)
                    .type(JOSEObjectType.JWT)
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .subject(subject)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build(),
            ).apply { sign(RSASSASigner(signingKey)) }
                .serialize()

        private val slowCompleted = AtomicInteger()

        private val backend: DisposableServer =
            HttpServer
                .create()
                .host("localhost")
                .port(0)
                .route { routes ->
                    routes.get("/jwks") { _, response ->
                        response.sendString(Mono.just(JWKSet(signingKey.toPublicJWK()).toString()))
                    }
                    // Echoes what the gateway stamped, so a case can check the forwarded identity.
                    routes.get("/v1/me") { request, response ->
                        response.sendString(Mono.just(request.requestHeaders().get("X-User-ID") ?: "none"))
                    }
                    routes.post("/v1/auth/login") { _, response -> response.sendString(Mono.just("logged-in")) }
                    routes.get("/v1/posts/slow") { _, response ->
                        response.sendString(
                            Mono
                                .delay(Duration.ofMillis(1500))
                                .map { SLOW_BODY }
                                .doOnSuccess { slowCompleted.incrementAndGet() },
                        )
                    }
                }.bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun backendRoute(registry: DynamicPropertyRegistry) {
            // The whole issuers[0] entry lives here: Boot binds an indexed list from a single
            // property source, so splitting it with the annotation's properties loses fields.
            registry.add("app.config.jwt.issuers[0].issuer") { ISSUER }
            registry.add("app.config.jwt.issuers[0].audience") { AUDIENCE }
            registry.add("app.config.jwt.issuers[0].jwks-uri") { "http://localhost:${backend.port()}/jwks" }
            registry.add("spring.cloud.gateway.server.webflux.routes[0].id") { "stub-backend" }
            registry.add("spring.cloud.gateway.server.webflux.routes[0].uri") { "http://localhost:${backend.port()}" }
            registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]") { "Path=/v1/**" }
        }
    }
}
