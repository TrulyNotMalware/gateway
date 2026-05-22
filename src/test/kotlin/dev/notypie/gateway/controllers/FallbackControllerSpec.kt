package dev.notypie.gateway.controllers

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class FallbackControllerSpec :
    BehaviorSpec({

        given("FallbackController") {
            val controller = FallbackController()

            `when`("/fallback/be 가 호출되면") {
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/fallback/be")
                            .header("X-Request-ID", "req-be-123"),
                    )
                val resp = controller.blogFallback(exchange)
                then("503 + 정형 body 반환 + requestId 포함") {
                    resp.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    val body = resp.body!!
                    body["code"] shouldBe "CIRCUIT_OPEN"
                    body["target"] shouldBe "blog-be"
                    body["requestId"] shouldBe "req-be-123"
                    body.containsKey("timestamp") shouldBe true
                }
            }

            `when`("/fallback/generic 이 X-Request-ID 없이 호출되면") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/fallback/generic"))
                val resp = controller.genericFallback(exchange)
                then("503 + downstream target + requestId=null") {
                    resp.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    resp.body!!["target"] shouldBe "downstream"
                    resp.body!!["requestId"] shouldBe null
                }
            }
        }
    })
