package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.InMemoryModule
import io.kotest.core.spec.style.BehaviorSpec
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

            `when`("all identifiers are null") {
                val svc = BlacklistService(InMemoryModule())
                then("returns false (nothing to check)") {
                    svc.isAnyBlacklisted("", null) shouldBe false
                }
            }

            `when`("getBlacklistByType is called") {
                val mod = InMemoryModule()
                val svc = BlacklistService(mod)
                svc.addIpToBlacklist("1.1.1.1")
                svc.addIpToBlacklist("2.2.2.2")
                then("only keys of that type are returned") {
                    val ips = svc.getBlacklistByType(BlacklistType.IP)
                    ips.size shouldBe 2
                }
            }
        }
    })
