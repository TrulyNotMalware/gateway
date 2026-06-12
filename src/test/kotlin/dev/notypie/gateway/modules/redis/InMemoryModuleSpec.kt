package dev.notypie.gateway.modules.redis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

class InMemoryModuleSpec :
    BehaviorSpec({

        given("InMemoryModule starting empty") {
            `when`("a key is set with a TTL") {
                val mod = InMemoryModule()
                val ok = mod.set("k", "v", ttlSeconds = 60)
                then("set succeeds and get returns the value") {
                    ok shouldBe true
                    mod.get("k") shouldBe "v"
                    mod.exists("k") shouldBe true
                }
            }

            `when`("a key is set without a TTL") {
                val mod = InMemoryModule()
                mod.set("k", "v", ttlSeconds = null)
                then("it becomes a permanent entry with no expiry") {
                    mod.get("k") shouldBe "v"
                    mod.remainingTtl("k") shouldBe -1L
                }
            }

            `when`("get/exists/delete is called on a missing key") {
                val mod = InMemoryModule()
                then("null or false is returned") {
                    mod.get("missing") shouldBe null
                    mod.exists("missing") shouldBe false
                    mod.delete("missing") shouldBe false
                }
            }
        }

        given("InMemoryModule increment behavior") {
            `when`("incrementing for the first time") {
                val mod = InMemoryModule()
                val first = mod.increment("rate:user:1", count = 1, ttlSeconds = 60)
                then("the value is 1 and the TTL is set") {
                    first shouldBe 1L
                    mod.remainingTtl("rate:user:1") shouldBeGreaterThan 0L
                }
            }

            `when`("incrementing multiple times within the same window") {
                val mod = InMemoryModule()
                mod.increment("rate:user:2", count = 1, ttlSeconds = 60)
                val second = mod.increment("rate:user:2", count = 3, ttlSeconds = 60)
                then("the value accumulates and the TTL keeps its initial value") {
                    second shouldBe 4L
                }
            }
        }

        given("InMemoryModule TTL expiry") {
            `when`("time passes after setting a short TTL") {
                val mod = InMemoryModule()
                mod.set("expiring", "v", ttlSeconds = 1)
                delay(1100)
                then("the key is removed automatically") {
                    mod.get("expiring") shouldBe null
                    mod.exists("expiring") shouldBe false
                    mod.remainingTtl("expiring") shouldBe -2L
                }
            }
        }
    })
