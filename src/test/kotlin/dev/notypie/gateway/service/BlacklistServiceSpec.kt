package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.InMemoryModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class BlacklistServiceSpec :
    BehaviorSpec({

        given("BlacklistService 가 InMemoryModule 위에서 동작할 때") {
            `when`("IP 를 블랙리스트에 추가하면") {
                val svc = BlacklistService(InMemoryModule())
                svc.addIpToBlacklist("1.2.3.4")
                then("isBlacklisted 가 true 를 반환한다") {
                    svc.isBlacklisted(BlacklistType.IP, "1.2.3.4") shouldBe true
                    svc.isBlacklisted(BlacklistType.IP, "9.9.9.9") shouldBe false
                }
            }

            `when`("User 와 ApiKey 를 각각 차단하면") {
                val svc = BlacklistService(InMemoryModule())
                svc.addUserToBlacklist("alice", "abuse")
                svc.addApiKeyToBlacklist("key-xyz", "leak")
                then("타입별로 정확히 매칭된다") {
                    svc.isBlacklisted(BlacklistType.USER, "alice") shouldBe true
                    svc.isBlacklisted(BlacklistType.API_KEY, "key-xyz") shouldBe true
                    svc.isBlacklisted(BlacklistType.USER, "bob") shouldBe false
                }
            }

            `when`("isAnyBlacklisted 를 IP/User/ApiKey 함께 호출하면") {
                val svc = BlacklistService(InMemoryModule())
                svc.addUserToBlacklist("alice", "abuse")
                then("하나라도 매칭되면 true") {
                    svc.isAnyBlacklisted("1.2.3.4", "alice", null) shouldBe true
                    svc.isAnyBlacklisted("1.2.3.4", "bob", null) shouldBe false
                }
            }

            `when`("identifier 가 모두 null 이면") {
                val svc = BlacklistService(InMemoryModule())
                then("false 반환 (블랙체크 대상이 없으므로)") {
                    svc.isAnyBlacklisted("", null, null) shouldBe false
                }
            }

            `when`("getBlacklistByType 호출하면") {
                val mod = InMemoryModule()
                val svc = BlacklistService(mod)
                svc.addIpToBlacklist("1.1.1.1")
                svc.addIpToBlacklist("2.2.2.2")
                then("같은 타입의 키만 반환") {
                    val ips = svc.getBlacklistByType(BlacklistType.IP)
                    ips.size shouldBe 2
                }
            }
        }
    })
