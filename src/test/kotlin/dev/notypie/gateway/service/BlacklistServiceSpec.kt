package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.InMemoryModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class BlacklistServiceSpec :
    BehaviorSpec({

        given("BlacklistService backed by InMemoryModule") {
            `when`("an IP is added to the blacklist") {
                val svc = BlacklistService(InMemoryModule())
                svc.addIpToBlacklist("1.2.3.4")
                then("isBlacklisted returns true") {
                    svc.isBlacklisted(BlacklistType.IP, "1.2.3.4") shouldBe true
                    svc.isBlacklisted(BlacklistType.IP, "9.9.9.9") shouldBe false
                }
            }

            `when`("a user and an API key are blacklisted") {
                val svc = BlacklistService(InMemoryModule())
                svc.addUserToBlacklist("alice", "abuse")
                svc.addApiKeyToBlacklist("key-xyz", "leak")
                then("each type matches exactly") {
                    svc.isBlacklisted(BlacklistType.USER, "alice") shouldBe true
                    svc.isBlacklisted(BlacklistType.API_KEY, "key-xyz") shouldBe true
                    svc.isBlacklisted(BlacklistType.USER, "bob") shouldBe false
                }
            }

            `when`("isAnyBlacklisted is called with IP and user together") {
                val svc = BlacklistService(InMemoryModule())
                svc.addUserToBlacklist("alice", "abuse")
                then("returns true if any of them matches") {
                    svc.isAnyBlacklisted("1.2.3.4", "alice") shouldBe true
                    svc.isAnyBlacklisted("1.2.3.4", "bob") shouldBe false
                }
            }

            `when`("an entry is removed") {
                val svc = BlacklistService(InMemoryModule())
                svc.addIpToBlacklist("1.2.3.4")
                val removed = svc.remove(BlacklistType.IP, "1.2.3.4")
                val removedAgain = svc.remove(BlacklistType.IP, "1.2.3.4")
                then("the first removal reports true and the entry is gone") {
                    removed shouldBe true
                    removedAgain shouldBe false
                    svc.isBlacklisted(BlacklistType.IP, "1.2.3.4") shouldBe false
                }
            }

            `when`("entries are listed by type") {
                val svc = BlacklistService(InMemoryModule())
                svc.add(BlacklistType.IP, "1.1.1.1", "scanner")
                svc.add(BlacklistType.IP, "2.2.2.2", "scanner")
                svc.add(BlacklistType.USER, "mallory", "abuse")
                val ips = svc.list(BlacklistType.IP, 100)
                val users = svc.list(BlacklistType.USER, 100)
                then("only that type is returned, with the key prefix stripped") {
                    ips.map { it.value }.sorted() shouldBe listOf("1.1.1.1", "2.2.2.2")
                    users.map { it.value } shouldBe listOf("mallory")
                }
                then("the stored reason comes back with each entry") {
                    users.single().reason shouldBe "abuse"
                }
                then("a no-TTL entry reports -1, matching Redis conventions") {
                    users.single().ttlSeconds shouldBe -1L
                }
            }

            `when`("an entry is written with a TTL") {
                val svc = BlacklistService(InMemoryModule())
                svc.add(BlacklistType.IP, "3.3.3.3", "auto", ttlSeconds = 60)
                then("the listing reports the remaining TTL") {
                    svc.list(BlacklistType.IP, 100).single().ttlSeconds shouldBeGreaterThan 0L
                }
            }

            `when`("the listing limit is smaller than the number of entries") {
                val svc = BlacklistService(InMemoryModule())
                repeat(10) { svc.add(BlacklistType.IP, "10.0.0.$it", "bulk") }
                then("the result is capped so an admin call cannot walk an unbounded keyspace") {
                    svc.list(BlacklistType.IP, 3).size shouldBe 3
                }
            }

            `when`("a listing limit above MAX_LIST_LIMIT is requested") {
                val svc = BlacklistService(InMemoryModule())
                svc.add(BlacklistType.IP, "4.4.4.4", "x")
                then("it is clamped rather than rejected") {
                    svc.list(BlacklistType.IP, Int.MAX_VALUE).size shouldBe 1
                }
            }

            `when`("all identifiers are null") {
                val svc = BlacklistService(InMemoryModule())
                then("returns false (nothing to check)") {
                    svc.isAnyBlacklisted("", null) shouldBe false
                }
            }
        }
    })
