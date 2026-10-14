package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.configurations.RedisFailureMode
import dev.notypie.gateway.modules.redis.InMemoryModule
import dev.notypie.gateway.modules.redis.RedisModule
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.RateLimitService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class SecurityFilterSpec :
    BehaviorSpec({

        val jsonMapper: JsonMapper = JsonMapper.builder().build()
        val resolver = XForwardedRemoteAddressResolver.maxTrustedIndex(1)

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
            val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper, resolver)

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

        given("client IP resolution behind a proxy (XFF)") {
            // maxTrustedIndex(1) keys on the rightmost-trusted XFF entry (what istio appends),
            // ignoring any client-forged leftmost entry; with no XFF it falls back to the socket peer.
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            timeoutMs = 2000,
                            enableBlacklist = true,
                            enableRateLimit = true,
                            // Loose enough that only the blacklist can trip here.
                            ipMaxRequests = 100000,
                            userMaxRequests = 100000,
                            endpointMaxRequests = 100000,
                            windowSeconds = 60,
                        ),
                )

            `when`("a blacklisted real client hides behind a spoofed leftmost XFF entry") {
                val blacklist = BlacklistService(InMemoryModule())
                blacklist.addIpToBlacklist("1.1.1.1")
                val filter = SecurityFilter(blacklist, RateLimitService(InMemoryModule()), cfg, jsonMapper, resolver)

                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .header("X-Forwarded-For", "9.9.9.9, 1.1.1.1")
                            .remoteAddress(InetSocketAddress("10.0.0.1", 0)),
                    )
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("the filter keys on the trusted 1.1.1.1 and blocks it, not the forged 9.9.9.9") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    val body = ex.response.bodyAsString.awaitSingleOrNull() ?: ""
                    body shouldContain "BLACKLISTED"
                }
            }

            `when`("a normal request carries a single X-Forwarded-For entry") {
                val filter =
                    SecurityFilter(
                        BlacklistService(InMemoryModule()),
                        RateLimitService(InMemoryModule()),
                        cfg,
                        jsonMapper,
                        resolver,
                    )

                val ex =
                    MockServerWebExchange.from(
                        MockServerHttpRequest
                            .get("/v1/posts")
                            .header("X-Forwarded-For", "1.1.1.1")
                            .remoteAddress(InetSocketAddress("10.0.0.1", 0)),
                    )
                var passed = false
                val chain =
                    GatewayFilterChain {
                        passed = true
                        Mono.empty()
                    }
                filter.filter(ex, chain).awaitSingleOrNull()
                then("the IP resolves and the chain passes with RateLimit info stamped") {
                    passed shouldBe true
                    ex.response.headers.getFirst("X-RateLimit-Remaining") shouldNotBe null
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
                val filter = SecurityFilter(BlacklistService(InMemoryModule()), rateLimit, cfg, jsonMapper, resolver)

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
                val filter = SecurityFilter(BlacklistService(InMemoryModule()), rateLimit, cfg, jsonMapper, resolver)

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
                    resolver,
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
                    resolver,
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

        given("SecurityFilter when the downstream call is slow, fails or is cancelled") {
            // DefaultGatewayFilterChain.filter() wraps the rest of the chain in Mono.defer, so one
            // subscription here is one run of the remaining filters (NettyRoutingFilter included).
            class CountingChain(
                private val downstream: () -> Mono<Void>,
            ) : GatewayFilterChain {
                val runs = AtomicInteger()
                val completions = AtomicInteger()

                override fun filter(exchange: ServerWebExchange): Mono<Void> =
                    Mono.defer {
                        runs.incrementAndGet()
                        downstream().doOnSuccess { completions.incrementAndGet() }
                    }
            }

            fun filterFor(mode: RedisFailureMode) =
                SecurityFilter(
                    BlacklistService(InMemoryModule()),
                    RateLimitService(InMemoryModule()),
                    AppConfig(security = AppConfig.Security(timeoutMs = 100, redisFailureMode = mode)),
                    jsonMapper,
                    resolver,
                )

            fun upload(ip: String) =
                MockServerWebExchange.from(
                    MockServerHttpRequest
                        .post("/v1/files/upload/a")
                        .remoteAddress(InetSocketAddress(ip, 0)),
                )

            `when`("the backend answers after timeoutMs (mode=HYBRID_IN_MEMORY)") {
                val chain = CountingChain { Mono.delay(Duration.ofMillis(400)).then() }
                filterFor(RedisFailureMode.HYBRID_IN_MEMORY).filter(upload("6.6.8.1"), chain).awaitSingleOrNull()
                then("the downstream call runs once, to completion — timeoutMs bounds only the checks") {
                    chain.runs.get() shouldBe 1
                    chain.completions.get() shouldBe 1
                }
            }

            `when`("the backend answers after timeoutMs (mode=FAIL_CLOSED)") {
                val chain = CountingChain { Mono.delay(Duration.ofMillis(400)).then() }
                val ex = upload("6.6.8.2")
                filterFor(RedisFailureMode.FAIL_CLOSED).filter(ex, chain).awaitSingleOrNull()
                then("a slow but healthy backend is not turned into a 429") {
                    chain.runs.get() shouldBe 1
                    chain.completions.get() shouldBe 1
                    ex.response.statusCode shouldBe null
                }
            }

            `when`("the downstream call fails") {
                val chain = CountingChain { Mono.error(IllegalStateException("connection refused")) }
                val error =
                    shouldThrow<IllegalStateException> {
                        filterFor(RedisFailureMode.HYBRID_IN_MEMORY)
                            .filter(upload("6.6.8.3"), chain)
                            .awaitSingleOrNull()
                    }
                then("the error reaches the caller and the chain is not run again") {
                    error.message shouldBe "connection refused"
                    chain.runs.get() shouldBe 1
                }
            }

            `when`("the exchange is cancelled while the backend is in flight") {
                val chain = CountingChain { Mono.never() }
                val subscription =
                    filterFor(RedisFailureMode.HYBRID_IN_MEMORY)
                        .filter(upload("6.6.8.4"), chain)
                        .subscribe()
                eventually(2.seconds) { chain.runs.get() shouldBe 1 }
                subscription.dispose()
                delay(300)
                then("the cancellation is not treated as a failed check that re-runs the chain") {
                    chain.runs.get() shouldBe 1
                }
            }

            `when`("the exchange is cancelled while the checks are still running") {
                // A Redis call that never answers: the client goes away before the deadline.
                class StallingModule : RedisModule by InMemoryModule() {
                    val entered = AtomicBoolean()

                    override suspend fun increment(key: String, count: Long, ttlSeconds: Long): Long {
                        entered.set(true)
                        awaitCancellation()
                    }
                }
                val stalling = StallingModule()
                val chain = CountingChain { Mono.empty() }
                val subscription =
                    SecurityFilter(
                        BlacklistService(InMemoryModule()),
                        RateLimitService(stalling),
                        AppConfig(
                            security =
                                AppConfig.Security(
                                    timeoutMs = 5000,
                                    redisFailureMode = RedisFailureMode.HYBRID_IN_MEMORY,
                                ),
                        ),
                        jsonMapper,
                        resolver,
                    ).filter(upload("6.6.8.5"), chain).subscribe()
                eventually(2.seconds) { stalling.entered.get() shouldBe true }
                subscription.dispose()
                delay(300)
                then("the cancellation is rethrown, not handled as a failed check that lets the request through") {
                    chain.runs.get() shouldBe 0
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
                    resolver,
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
        given("SecurityFilter with the per-API-key dimension configured") {
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            timeoutMs = 2000,
                            enableBlacklist = false,
                            enableRateLimit = true,
                            // Generous so only the API-key dimension can trip.
                            ipMaxRequests = 1000,
                            userMaxRequests = 1000,
                            endpointMaxRequests = 1000,
                            windowSeconds = 60,
                            apiKeyMaxRequests = 2,
                            allowedApiKeys = listOf("known-key"),
                        ),
                )

            fun requestWith(apiKey: String?, ip: String): MockServerWebExchange {
                val builder = MockServerHttpRequest.get("/v1/posts").remoteAddress(InetSocketAddress(ip, 0))
                val ex = MockServerWebExchange.from(builder)
                // Stand in for TrustHeaderStripFilter(-200), which captures the inbound header
                // into this attribute and then removes it.
                apiKey?.let { ex.attributes[TrustHeaderStripFilter.CAPTURED_API_KEY_ATTR] = it }
                return ex
            }

            `when`("a recognised API key exceeds its own limit") {
                val blacklist = BlacklistService(InMemoryModule())
                val rateLimit = RateLimitService(InMemoryModule())
                val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper, resolver)
                repeat(2) {
                    filter
                        .filter(requestWith("known-key", "1.1.1.1"), GatewayFilterChain { Mono.empty() })
                        .awaitSingleOrNull()
                }
                val ex = requestWith("known-key", "1.1.1.1")
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the third request is blocked with 429 even though every other limit is far away") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }

            `when`("an unrecognised API key is supplied repeatedly") {
                val blacklist = BlacklistService(InMemoryModule())
                val rateLimit = RateLimitService(InMemoryModule())
                val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper, resolver)
                // A client rotating random keys must NOT earn a fresh bucket each time; these
                // requests are simply not counted in the API-key dimension at all.
                repeat(5) { i ->
                    filter
                        .filter(requestWith("random-$i", "2.2.2.2"), GatewayFilterChain { Mono.empty() })
                        .awaitSingleOrNull()
                }
                val ex = requestWith("random-99", "2.2.2.2")
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the request still passes — unknown keys never get a counter") {
                    passed shouldBe true
                }
            }

            `when`("a recognised key is used from a second IP") {
                val blacklist = BlacklistService(InMemoryModule())
                val rateLimit = RateLimitService(InMemoryModule())
                val filter = SecurityFilter(blacklist, rateLimit, cfg, jsonMapper, resolver)
                repeat(2) {
                    filter
                        .filter(requestWith("known-key", "3.3.3.3"), GatewayFilterChain { Mono.empty() })
                        .awaitSingleOrNull()
                }
                val ex = requestWith("known-key", "4.4.4.4")
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the counter follows the key, not the IP, so it is still blocked") {
                    passed shouldBe false
                    ex.response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }
        }

        given("SecurityFilter with allowedApiKeys left empty (the default)") {
            val cfg =
                AppConfig(
                    security =
                        AppConfig.Security(
                            timeoutMs = 2000,
                            enableBlacklist = false,
                            enableRateLimit = true,
                            ipMaxRequests = 1000,
                            userMaxRequests = 1000,
                            endpointMaxRequests = 1000,
                            windowSeconds = 60,
                            apiKeyMaxRequests = 1,
                        ),
                )
            val filter =
                SecurityFilter(
                    BlacklistService(InMemoryModule()),
                    RateLimitService(InMemoryModule()),
                    cfg,
                    jsonMapper,
                    resolver,
                )

            fun keyedRequest(apiKey: String): MockServerWebExchange {
                val request =
                    MockServerHttpRequest
                        .get("/v1/posts")
                        .remoteAddress(InetSocketAddress("6.6.6.6", 0))
                val exchange = MockServerWebExchange.from(request)
                exchange.attributes[TrustHeaderStripFilter.CAPTURED_API_KEY_ATTR] = apiKey
                return exchange
            }

            `when`("requests arrive carrying an API key") {
                repeat(3) {
                    filter
                        .filter(keyedRequest("any-key"), GatewayFilterChain { Mono.empty() })
                        .awaitSingleOrNull()
                }
                val ex = keyedRequest("any-key")
                var passed = false
                filter
                    .filter(
                        ex,
                        GatewayFilterChain {
                            passed = true
                            Mono.empty()
                        },
                    ).awaitSingleOrNull()
                then("the dimension is inert and nothing is throttled by it") {
                    passed shouldBe true
                }
            }
        }
    })
