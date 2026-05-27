package dev.notypie.gateway.filters.global

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class RequestIdFilterSpec :
    BehaviorSpec({

        given("RequestIdFilter") {
            val filter = RequestIdFilter()

            `when`("the request has no X-Request-ID header") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var downstream: ServerWebExchange? = null
                val chain =
                    WebFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("a generated UUID is propagated downstream and echoed on the response") {
                    val downstreamId = downstream!!.request.headers.getFirst("X-Request-ID")
                    val responseId = exchange.response.headers.getFirst("X-Request-ID")
                    downstreamId shouldNotBe null
                    responseId shouldBe downstreamId
                }
            }

            `when`("the request carries a valid X-Request-ID header") {
                val existing = "req-abc-123"
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/posts").header("X-Request-ID", existing),
                    )
                var downstream: ServerWebExchange? = null
                val chain =
                    WebFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("the supplied value is propagated as-is") {
                    downstream!!.request.headers.getFirst("X-Request-ID") shouldBe existing
                    exchange.response.headers.getFirst("X-Request-ID") shouldBe existing
                }
            }

            `when`("an external X-Request-ID is too long or contains disallowed characters") {
                val bad = "x".repeat(300)
                val injection = "abc\r\nSet-Cookie: evil=1"
                val exA =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/posts").header("X-Request-ID", bad),
                    )
                val exB =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/posts").header("X-Request-ID", injection),
                    )
                var dsA: ServerWebExchange? = null
                var dsB: ServerWebExchange? = null
                filter
                    .filter(
                        exA,
                        WebFilterChain { ex ->
                            dsA = ex
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                filter
                    .filter(
                        exB,
                        WebFilterChain { ex ->
                            dsB = ex
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()

                then("malicious input is rejected and a fresh UUID is used") {
                    dsA!!.request.headers.getFirst("X-Request-ID") shouldNotBe bad
                    dsA!!.request.headers.getFirst("X-Request-ID") shouldNotBe null
                    dsB!!.request.headers.getFirst("X-Request-ID") shouldNotBe injection
                    dsB!!.request.headers.getFirst("X-Request-ID") shouldNotBe null
                }
            }
        }
    })
