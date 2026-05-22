package dev.notypie.gateway.modules.redis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

class InMemoryModuleSpec :
    BehaviorSpec({

        given("InMemoryModule 가 비어있는 상태") {
            `when`("TTL 과 함께 set 하면") {
                val mod = InMemoryModule()
                val ok = mod.set("k", "v", ttlSeconds = 60)
                then("set 성공하고 get 으로 값을 읽을 수 있다") {
                    ok shouldBe true
                    mod.get("k") shouldBe "v"
                    mod.exists("k") shouldBe true
                }
            }

            `when`("TTL 없이 set 하면") {
                val mod = InMemoryModule()
                mod.set("k", "v", ttlSeconds = null)
                then("expiry 없는 영구 엔트리가 된다") {
                    mod.get("k") shouldBe "v"
                    mod.remainingTtl("k") shouldBe -1L
                }
            }

            `when`("없는 키에 대해 get/exists/delete 를 호출하면") {
                val mod = InMemoryModule()
                then("null/false 반환") {
                    mod.get("missing") shouldBe null
                    mod.exists("missing") shouldBe false
                    mod.delete("missing") shouldBe false
                }
            }
        }

        given("InMemoryModule 의 increment 동작") {
            `when`("처음 increment 하면") {
                val mod = InMemoryModule()
                val first = mod.increment("rate:user:1", count = 1, ttlSeconds = 60)
                then("값이 1 이고 TTL 이 설정된다") {
                    first shouldBe 1L
                    mod.remainingTtl("rate:user:1") shouldBeGreaterThan 0L
                }
            }

            `when`("같은 윈도우에서 여러 번 increment 하면") {
                val mod = InMemoryModule()
                mod.increment("rate:user:2", count = 1, ttlSeconds = 60)
                val second = mod.increment("rate:user:2", count = 3, ttlSeconds = 60)
                then("값이 누적되고 TTL 은 초기 set 값을 유지한다") {
                    second shouldBe 4L
                }
            }
        }

        given("InMemoryModule 의 TTL 만료") {
            `when`("짧은 TTL 로 set 한 뒤 시간이 흐르면") {
                val mod = InMemoryModule()
                mod.set("expiring", "v", ttlSeconds = 1)
                delay(1100)
                then("키가 자동으로 제거된다") {
                    mod.get("expiring") shouldBe null
                    mod.exists("expiring") shouldBe false
                    mod.remainingTtl("expiring") shouldBe -2L
                }
            }
        }

        given("InMemoryModule 의 패턴 검색") {
            `when`("여러 prefix 의 키를 set 한 뒤 패턴으로 조회하면") {
                val mod = InMemoryModule()
                mod.set("blacklist:ip:1.1.1.1", "1")
                mod.set("blacklist:ip:2.2.2.2", "1")
                mod.set("blacklist:user:alice", "spam")
                then("패턴에 매칭되는 키만 반환한다") {
                    val ips = mod.getKeysByPattern("blacklist:ip:*")
                    ips shouldContainAll setOf("blacklist:ip:1.1.1.1", "blacklist:ip:2.2.2.2")
                    ips.contains("blacklist:user:alice") shouldBe false
                }
            }
        }
    })
