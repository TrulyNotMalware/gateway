package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.modules.redis.InMemoryModule
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.RateLimitService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

class SecurityFilterSpec :
    BehaviorSpec({

        val jsonMapper: JsonMapper = JsonMapper.builder().build()

        given("Blacklist=on, RateLimit=on 인 SecurityFilter") {
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            timeoutMs = 2000,
                            enableBlacklist = true,
                            enableRateLimit = true,
                            ipMaxRequests = 1,
                            userMaxRequests = 1000,
                            apiKeyMaxRequests = 1000,
                            endpointMaxRequests = 1000,
                            windowSeconds = 60,
                        ),
                )
            val blacklist = BlacklistService(InMemoryModule())
            val rateLimit = RateLimitService(InMemoryModule())
            val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper)

            `when`("정상 요청이 한 번 들어오면") {
                val ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("체인이 통과하고 응답 헤더에 RateLimit 정보가 박힌다") {
                    passed shouldBe true
                    ex.response.headers.getFirst("X-RateLimit-Remaining") shouldNotBe null
                }
            }

            `when`("IP 가 블랙리스트에 있으면") {
                blacklist.addIpToBlacklist("9.9.9.9")
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("9.9.9.9", 0)),
                    )
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("체인 통과가 차단되고 403 반환") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    val body = ex.response.bodyAsString.awaitSingleOrNull() ?: ""
                    body shouldContain "BLACKLISTED"
                }
            }

            `when`("IP limit 을 초과하면") {
                val warmup =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("8.8.8.8", 0)),
                    )
                filter.filter(warmup, GatewayFilterChain { Mono.empty() }).awaitSingleOrNull()

                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("8.8.8.8", 0)),
                    )
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("두 번째 요청은 429 차단") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }
        }

        given("Security 가 모두 비활성화된 경우") {
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            enableBlacklist = false,
                            enableRateLimit = false,
                        ),
                )
            val filter =
                SecurityFilter(
                    BlacklistService(InMemoryModule()),
                    RateLimitService(InMemoryModule()),
                    cfg,
                    jsonMapper,
                )

            `when`("어떤 요청이 들어와도") {
                val ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("체인을 그대로 통과") {
                    passed shouldBe true
                }
            }
        }
    })
