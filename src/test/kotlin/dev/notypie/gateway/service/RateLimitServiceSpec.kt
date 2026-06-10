package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.InMemoryModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class RateLimitServiceSpec :
    BehaviorSpec({

        given("RateLimitService backed by InMemoryModule") {
            `when`("requests stay within the limit") {
                val svc = RateLimitService(InMemoryModule())
                val result = svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60)
                then("allowed=true and the remaining counter is non-negative") {
                    result.allowed shouldBe true
                    result.remaining shouldBeGreaterThanOrEqual 0L
                }
            }

            `when`("the same key is hit beyond its limit") {
                val svc = RateLimitService(InMemoryModule())
                repeat(5) { svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60) }
                val over = svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60)
                then("allowed=false is returned") {
                    over.allowed shouldBe false
                }
            }

            `when`("checkMultipleRateLimits compares counters") {
                val mod = InMemoryModule()
                val svc = RateLimitService(mod)
                // Prime the user counter to 1; with user limit=2, remaining=1 should be the smallest.
                svc.checkUserRateLimit("alice", maxRequests = 2, windowSeconds = 60)
                val result =
                    svc.checkMultipleRateLimits(
                        ip = "1.2.3.4",
                        userId = "alice",
                        endpoint = null,
                        limits =
                            RateLimitConfig(
                                ipMaxRequests = 1000,
                                userMaxRequests = 2,
                                windowSeconds = 60,
                            ),
                    )
                then("the tighter user counter dominates the remaining result") {
                    result.allowed shouldBe true
                    // user is now at 2 calls; limit=2 → remaining=0.
                    result.remaining shouldBe 0L
                }
            }

            `when`("all identifiers are null") {
                val svc = RateLimitService(InMemoryModule())
                val result = svc.checkMultipleRateLimits(null, null, null)
                then("the default allowed result is returned") {
                    result.allowed shouldBe true
                }
            }
        }
    })
