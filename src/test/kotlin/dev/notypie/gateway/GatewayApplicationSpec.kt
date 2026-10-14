package dev.notypie.gateway

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

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
        "app.config.jwt.issuers[0].issuer=test",
        "app.config.jwt.issuers[0].jwks-uri=http://localhost:1/unused",
        // The prod value: the deadline for the blacklist / rate-limit checks.
        "app.config.security.timeout-ms=1000",
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
        }
    }

    companion object {
        private const val SLOW_BODY = "slow-body"
        private val slowCompleted = AtomicInteger()

        private val backend: DisposableServer =
            HttpServer
                .create()
                .host("localhost")
                .port(0)
                .route { routes ->
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
            registry.add("spring.cloud.gateway.server.webflux.routes[0].id") { "stub-backend" }
            registry.add("spring.cloud.gateway.server.webflux.routes[0].uri") { "http://localhost:${backend.port()}" }
            registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]") { "Path=/v1/**" }
        }
    }
}
