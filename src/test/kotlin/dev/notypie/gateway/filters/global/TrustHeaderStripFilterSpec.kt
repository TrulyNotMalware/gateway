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

        given("기본 strip 리스트로 동작하는 TrustHeaderStripFilter") {
            val filter = TrustHeaderStripFilter(AppConfig())

            `when`("외부에서 X-User-ID, X-API-Key 를 박아 들어오면") {
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

                then("신뢰 헤더는 stripped, 그 외 헤더는 유지") {
                    val ds = downstream!!.request.headers
                    ds.getFirst("X-User-ID") shouldBe null
                    ds.getFirst("X-API-Key") shouldBe null
                    ds.getFirst("X-Request-ID") shouldBe "should-survive"
                }
            }

            `when`("strip 대상 헤더가 하나도 없으면") {
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

                then("요청이 그대로 다운스트림에 전달된다") {
                    downstream!!.request.headers.getFirst("X-Request-ID") shouldBe "rid"
                }
            }
        }

        given("strip 리스트가 비어있으면") {
            val cfg =
                AppConfig(
                    security = AppConfig.Security(strippedTrustHeaders = emptyList()),
                )
            val filter = TrustHeaderStripFilter(cfg)
            `when`("X-User-ID 가 들어와도") {
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
                then("strip 없이 그대로 통과") {
                    downstream!!.request.headers.getFirst("X-User-ID") shouldBe "passthrough"
                }
            }
        }
    })
