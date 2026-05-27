package dev.notypie.gateway.filters.global

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The positive path (authenticated SecurityContext → X-User-ID injection) depends on Spring WebFlux's
 * reactor Context propagation and is hard to reproduce reliably in unit tests. That branch is covered
 * by the prod boot smoke test with a real JWT.
 *
 * This spec only guards the negative path (public path, no authentication): no header is added and
 * the chain passes through unchanged.
 */
class JwtUserIdInjectionFilterSpec :
    BehaviorSpec({

        given("JwtUserIdInjectionFilter") {
            val filter = JwtUserIdInjectionFilter()

            `when`("SecurityContext has no authentication (public path)") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var downstream: ServerWebExchange? = null
                val chain =
                    GatewayFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }

                filter.filter(exchange, chain).awaitSingleOrNull()

                then("no X-User-ID is stamped and the chain passes through") {
                    downstream shouldBe exchange
                    downstream!!.request.headers.getFirst("X-User-ID") shouldBe null
                }
            }
        }
    })
