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

            `when`("the filter chain runs and the response commits") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                val chain = WebFilterChain { Mono.empty() }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("required security headers are stamped on the response") {
                    val h = exchange.response.headers
                    h.getFirst("Strict-Transport-Security") shouldBe
                        "max-age=31536000; includeSubDomains"
                    h.getFirst("X-Content-Type-Options") shouldBe "nosniff"
                    h.getFirst("X-Frame-Options") shouldBe "DENY"
                    h.getFirst("Referrer-Policy") shouldBe "strict-origin-when-cross-origin"
                    h.getFirst("Permissions-Policy") shouldBe "geolocation=(), microphone=(), camera=()"
                }
            }

            `when`("downstream has already set X-Frame-Options") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                val chain =
                    WebFilterChain { ex ->
                        ex.response.headers["X-Frame-Options"] = "SAMEORIGIN"
                        Mono.empty()
                    }
                filter.filter(exchange, chain).awaitSingleOrNull()
                exchange.response.setComplete().awaitSingleOrNull()

                then("the downstream value is preserved, not overwritten") {
                    exchange.response.headers.getFirst("X-Frame-Options") shouldBe "SAMEORIGIN"
                }
            }
        }
    })
