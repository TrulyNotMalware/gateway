package dev.notypie.gateway.filters.global

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class JwtUserIdInjectionFilterSpec :
    BehaviorSpec({

        // The real DefaultGatewayFilterChain.filter() is a Mono.defer: building the Mono runs nothing,
        // each subscription runs the rest of the chain. Count subscriptions, not calls.
        class RecordingChain : GatewayFilterChain {
            val runs = AtomicInteger()
            val seen = mutableListOf<ServerWebExchange>()

            override fun filter(exchange: ServerWebExchange): Mono<Void> =
                Mono.defer {
                    runs.incrementAndGet()
                    seen += exchange
                    Mono.empty()
                }
        }

        given("JwtUserIdInjectionFilter") {
            val filter = JwtUserIdInjectionFilter()

            then("it runs between TrustHeaderStripFilter(-200) and SecurityFilter(-100)") {
                filter.order shouldBe -150
            }

            `when`("SecurityContext has no authentication (public path)") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                val chain = RecordingChain()

                filter.filter(exchange, chain).awaitSingleOrNull()

                then("no X-User-ID is stamped and the chain runs once, unchanged") {
                    chain.runs.get() shouldBe 1
                    chain.seen.single() shouldBe exchange
                    chain.seen
                        .single()
                        .request.headers
                        .getFirst("X-User-ID") shouldBe null
                }
            }

            `when`("SecurityContext holds an authenticated JwtAuthenticationToken") {
                val jwt =
                    Jwt
                        .withTokenValue("token")
                        .header("alg", "RS256")
                        .claim("sub", "user-1")
                        .build()
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/me"))
                val chain = RecordingChain()

                filter
                    .filter(exchange, chain)
                    .contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt, emptyList())),
                    ).awaitSingleOrNull()

                then("X-User-ID carries the token's sub and the rest of the chain runs exactly once") {
                    chain.runs.get() shouldBe 1
                    chain.seen
                        .single()
                        .request.headers
                        .getFirst("X-User-ID") shouldBe "user-1"
                }
            }
        }
    })
