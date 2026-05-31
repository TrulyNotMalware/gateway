package dev.notypie.gateway.modules.redis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class InMemoryRateLimitFallbackSpec :
    BehaviorSpec({

        given("increment within the same window") {
            `when`("called repeatedly with the same key") {
                val fallback = InMemoryRateLimitFallback()
                val first = fallback.increment("k", 1, ttlSeconds = 60)
                val second = fallback.increment("k", 1, ttlSeconds = 60)
                val third = fallback.increment("k", 3, ttlSeconds = 60)
                then("counter accumulates and ttl-on-create is not reset by subsequent increments") {
                    first shouldBe 1L
                    second shouldBe 2L
                    third shouldBe 5L
                    // remainingTtl reads the deadline set by the FIRST increment.
                    fallback.remainingTtl("k").shouldBeBetween(58L, 60L)
                }
            }

            `when`("a different key is incremented") {
                val fallback = InMemoryRateLimitFallback()
                fallback.increment("a", 1, 60)
                fallback.increment("b", 1, 60)
                then("counters are isolated per key") {
                    // Re-increment a; should be 2 (own counter), not 3 (shared).
                    fallback.increment("a", 1, 60) shouldBe 2L
                }
            }
        }

        given("increment after the window has expired") {
            `when`("ttl elapsed between increments") {
                val fallback = InMemoryRateLimitFallback()
                // 1-second window — keep this small so the test is fast but still
                // exercises real wall-clock expiry rather than mocked time.
                fallback.increment("k", 5, ttlSeconds = 1)
                Thread.sleep(1_100)
                val afterReset = fallback.increment("k", 1, ttlSeconds = 60)
                then("counter restarts from the new value with a fresh window") {
                    afterReset shouldBe 1L
                    fallback.remainingTtl("k").shouldBeGreaterThan(0L)
                }
            }
        }

        given("remainingTtl") {
            `when`("the key was never incremented") {
                val fallback = InMemoryRateLimitFallback()
                then("returns -2 (matches Redis `TTL` semantics for missing keys)") {
                    fallback.remainingTtl("nope") shouldBe -2L
                }
            }

            `when`("the key existed but its window expired") {
                val fallback = InMemoryRateLimitFallback()
                fallback.increment("k", 1, ttlSeconds = 1)
                Thread.sleep(1_100)
                then("returns -2 and cleans up the expired entry") {
                    fallback.remainingTtl("k") shouldBe -2L
                }
            }
        }
    })
