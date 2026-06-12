package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.configurations.RedisFailureMode
import dev.notypie.gateway.modules.redis.InMemoryModule
import dev.notypie.gateway.modules.redis.RedisModule
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.RateLimitService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
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
                            endpointMaxRequests = 1000,
                            windowSeconds = 60,
                        ),
                )
            val blacklist = BlacklistService(InMemoryModule())
            val rateLimit = RateLimitService(InMemoryModule())
            val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper)

            `when`("a single normal request arrives") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("5.5.5.5", 0)),
                    )
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

        given("SecurityFilter with a tight login rate limit") {
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            timeoutMs = 2000,
                            enableBlacklist = false,
                            enableRateLimit = true,
                            // Keep the generic dimensions loose so only the login counter can trip.
                            ipMaxRequests = 100000,
                            userMaxRequests = 100000,
                            endpointMaxRequests = 100000,
                            windowSeconds = 60,
                            loginMaxRequests = 10,
                            loginWindowSeconds = 60,
                            loginPaths = listOf("/v1/auth/login"),
                        ),
                )

            `when`("the 11th login attempt within the window arrives from the same IP") {
                val rateLimit = RateLimitService(InMemoryModule())
                val filter = SecurityFilter(BlacklistService(InMemoryModule()), rateLimit, cfg, jsonMapper)

                fun loginRequest() =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .post("/v1/auth/login")
                            .remoteAddress(InetSocketAddress("7.7.7.7", 0)),
                    )

                repeat(10) { filter.filter(loginRequest(), GatewayFilterChain { Mono.empty() }).awaitSingleOrNull() }

                val ex = loginRequest()
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the 11th login is blocked with 429") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }

            `when`("a non-login path is hit many times from the same IP") {
                val rateLimit = RateLimitService(InMemoryModule())
                val filter = SecurityFilter(BlacklistService(InMemoryModule()), rateLimit, cfg, jsonMapper)

                fun postsRequest() =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("7.7.7.7", 0)),
                    )

                repeat(10) { filter.filter(postsRequest(), GatewayFilterChain { Mono.empty() }).awaitSingleOrNull() }

                val ex = postsRequest()
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("it is unaffected by the login counter and still passes") {
                    passed shouldBe true
                }
            }
        }

        given("SecurityFilter when the security check times out") {
            // A RedisModule whose `increment` parks forever — simulates a stalled Redis
            // call that never reaches `ReactiveRedissonClientModule`'s per-call dispatch
            // (which would otherwise have routed by `redisFailureMode` itself).
            class StallingRedisModule : RedisModule {
                override suspend fun set(key: String, value: String, ttlSeconds: Long?): Boolean = false

                override suspend fun get(key: String): String? = null

                override suspend fun exists(key: String): Boolean = false

                override suspend fun delete(key: String): Boolean = false

                override suspend fun increment(key: String, count: Long, ttlSeconds: Long): Long {
                    // Long enough to outrun any reasonable timeoutMs in tests but cooperative
                    // (delay is cancellable, so withTimeout can interrupt it cleanly).
                    delay(10_000)
                    return 0L
                }

                override suspend fun remainingTtl(key: String): Long = -1L
            }

            fun filterFor(mode: RedisFailureMode): SecurityFilter {
                val cfg =
                    AppConfig(
                        security =
                            AppConfig.Security(
                                timeoutMs = 100,
                                enableBlacklist = false,
                                enableRateLimit = true,
                                redisFailureMode = mode,
                            ),
                    )
                return SecurityFilter(
                    BlacklistService(InMemoryModule()),
                    RateLimitService(StallingRedisModule()),
                    cfg,
                    jsonMapper,
                )
            }

            `when`("mode=FAIL_OPEN and Redis stalls past the deadline") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.6.1", 0)),
                    )
                var passed = false
                filterFor(RedisFailureMode.FAIL_OPEN)
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the chain still passes — availability preferred over correctness") {
                    passed shouldBe true
                }
            }

            `when`("mode=FAIL_CLOSED and Redis stalls past the deadline") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.6.2", 0)),
                    )
                var passed = false
                filterFor(RedisFailureMode.FAIL_CLOSED)
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then(
                    "the request is blocked with 429 — without this, FAIL_CLOSED would silently degrade to fail-open on slow Redis",
                ) {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }

            `when`("mode=HYBRID_IN_MEMORY and Redis stalls past the deadline") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.6.3", 0)),
                    )
                var passed = false
                filterFor(RedisFailureMode.HYBRID_IN_MEMORY)
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then(
                    "the chain passes — HYBRID's contract is 'throttle when Redis is unreachable', not 'deny on slow Redis'",
                ) {
                    passed shouldBe true
                }
            }
        }

        given("SecurityFilter when the blacklist lookup itself fails") {
            // Mirrors ReactiveRedissonClientModule.exists, which deliberately propagates
            // Redis failures instead of fabricating `false` — otherwise the blacklist
            // would silently turn off regardless of redisFailureMode.
            class ThrowingExistsModule : RedisModule {
                override suspend fun set(key: String, value: String, ttlSeconds: Long?): Boolean = false

                override suspend fun get(key: String): String? = null

                override suspend fun exists(key: String): Boolean = error("redis down")

                override suspend fun delete(key: String): Boolean = false

                override suspend fun increment(key: String, count: Long, ttlSeconds: Long): Long = 0L

                override suspend fun remainingTtl(key: String): Long = -1L
            }

            fun filterFor(mode: RedisFailureMode): SecurityFilter {
                val cfg =
                    AppConfig(
                        security =
                            AppConfig.Security(
                                timeoutMs = 2000,
                                enableBlacklist = true,
                                enableRateLimit = false,
                                redisFailureMode = mode,
                            ),
                    )
                return SecurityFilter(
                    BlacklistService(ThrowingExistsModule()),
                    RateLimitService(InMemoryModule()),
                    cfg,
                    jsonMapper,
                )
            }

            `when`("mode=FAIL_CLOSED and the blacklist check throws") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.7.1", 0)),
                    )
                var passed = false
                filterFor(RedisFailureMode.FAIL_CLOSED)
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the request is denied — the blacklist must not silently disable") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }

            `when`("mode=FAIL_OPEN and the blacklist check throws") {
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.7.2", 0)),
                    )
                var passed = false
                filterFor(RedisFailureMode.FAIL_OPEN)
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the chain passes — availability preferred, per the configured stance") {
                    passed shouldBe true
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
                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .remoteAddress(InetSocketAddress("6.6.6.4", 0)),
                    )
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

            `when`("a request arrives with no resolvable peer address") {
                // Even with every check disabled the filter refuses an address-less
                // request: a null remoteAddress would otherwise collapse all such
                // requests into one shared rate-limit identity ("unknown").
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
                then("the request is refused with 403 ACCESS_DENIED") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    val body = ex.response.bodyAsString.awaitSingleOrNull() ?: ""
                    body shouldContain "ACCESS_DENIED"
                }
            }
        }
    })
