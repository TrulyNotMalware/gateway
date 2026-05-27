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

        given("SecurityFilter with Blacklist=on, RateLimit=on") {
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

            `when`("a single normal request arrives") {
                val ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("the chain passes and RateLimit info is stamped on the response headers") {
                    passed shouldBe true
                    ex.response.headers.getFirst("X-RateLimit-Remaining") shouldNotBe null
                }
            }

            `when`("the IP is blacklisted") {
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
                then("the chain is blocked and 403 is returned") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    val body = ex.response.bodyAsString.awaitSingleOrNull() ?: ""
                    body shouldContain "BLACKLISTED"
                }
            }

            `when`("the IP rate limit is exceeded") {
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
                then("the second request is blocked with 429") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }
        }

        given("SecurityFilter with all checks disabled") {
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

            `when`("any request arrives") {
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
                then("the chain passes through") {
                    passed shouldBe true
                }
            }
        }
    })
