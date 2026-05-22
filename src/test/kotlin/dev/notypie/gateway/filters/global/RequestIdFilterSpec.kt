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

            `when`("요청에 X-Request-ID 헤더가 없으면") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var downstream: ServerWebExchange? = null
                val chain =
                    WebFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("UUID 가 생성되어 다운스트림 요청과 응답 헤더에 모두 박힌다") {
                    val downstreamId = downstream!!.request.headers.getFirst("X-Request-ID")
                    val responseId = exchange.response.headers.getFirst("X-Request-ID")
                    downstreamId shouldNotBe null
                    responseId shouldBe downstreamId
                }
            }

            `when`("요청이 유효한 X-Request-ID 헤더를 가지고 있으면") {
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

                then("기존 값이 그대로 전파된다") {
                    downstream!!.request.headers.getFirst("X-Request-ID") shouldBe existing
                    exchange.response.headers.getFirst("X-Request-ID") shouldBe existing
                }
            }

            `when`("외부에서 박힌 X-Request-ID 가 너무 길거나 허용 안되는 문자를 포함하면") {
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

                then("악성 입력은 무시되고 새 UUID 가 사용된다") {
                    dsA!!.request.headers.getFirst("X-Request-ID") shouldNotBe bad
                    dsA!!.request.headers.getFirst("X-Request-ID") shouldNotBe null
                    dsB!!.request.headers.getFirst("X-Request-ID") shouldNotBe injection
                    dsB!!.request.headers.getFirst("X-Request-ID") shouldNotBe null
                }
            }
        }
    })
