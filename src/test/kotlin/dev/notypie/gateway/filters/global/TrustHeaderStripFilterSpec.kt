package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class TrustHeaderStripFilterSpec :
    BehaviorSpec({

        given("TrustHeaderStripFilter with the default strip list") {
            val filter = TrustHeaderStripFilter(AppConfig())

            `when`("external X-User-ID and X-API-Key headers arrive") {
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .header("X-User-ID", "spoofed-user")
                            .header("X-API-Key", "spoofed-key")
                            .header("X-Request-ID", "should-survive"),
                    )
                var downstream: ServerWebExchange? = null
                val chain =
                    GatewayFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()

                then("trusted headers are stripped; other headers are preserved") {
                    val ds = downstream!!.request.headers
                    ds.getFirst("X-User-ID") shouldBe null
                    ds.getFirst("X-API-Key") shouldBe null
                    ds.getFirst("X-Request-ID") shouldBe "should-survive"
                }
            }

            `when`("no strip-target header is present") {
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/posts").header("X-Request-ID", "rid"),
                    )
                var downstream: ServerWebExchange? = null
                val chain =
                    GatewayFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()

                then("the request is forwarded downstream unchanged") {
                    downstream!!.request.headers.getFirst("X-Request-ID") shouldBe "rid"
                }
            }
        }

        given("TrustHeaderStripFilter with an empty strip list") {
            val cfg =
                AppConfig(
                    security = AppConfig.Security(strippedTrustHeaders = emptyList()),
                )
            val filter = TrustHeaderStripFilter(cfg)
            `when`("X-User-ID arrives") {
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/posts").header("X-User-ID", "passthrough"),
                    )
                var downstream: ServerWebExchange? = null
                val chain =
                    GatewayFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                then("it passes through without stripping") {
                    downstream!!.request.headers.getFirst("X-User-ID") shouldBe "passthrough"
                }
            }
        }
    })
