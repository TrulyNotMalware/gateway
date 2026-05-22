package dev.notypie.gateway.filters.global

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class SecurityHeadersFilterSpec :
    BehaviorSpec({

        given("SecurityHeadersFilter") {
            val filter = SecurityHeadersFilter()

            `when`("필터 체인이 통과한 뒤 응답이 commit 되면") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                val chain = WebFilterChain { Mono.empty() }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("필수 보안 헤더가 응답에 박힌다") {
                    val h = exchange.response.headers
                    h.getFirst("Strict-Transport-Security") shouldBe
                        "max-age=31536000; includeSubDomains"
                    h.getFirst("X-Content-Type-Options") shouldBe "nosniff"
                    h.getFirst("X-Frame-Options") shouldBe "DENY"
                    h.getFirst("Referrer-Policy") shouldBe "strict-origin-when-cross-origin"
                    h.getFirst("Permissions-Policy") shouldBe "geolocation=(), microphone=(), camera=()"
                }
            }

            `when`("다운스트림이 이미 X-Frame-Options 를 박은 경우") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                val chain =
                    WebFilterChain { ex ->
                        ex.response.headers["X-Frame-Options"] = "SAMEORIGIN"
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("다운스트림의 값을 덮어쓰지 않고 유지한다") {
                    exchange.response.headers.getFirst("X-Frame-Options") shouldBe "SAMEORIGIN"
                }
            }
        }
    })
