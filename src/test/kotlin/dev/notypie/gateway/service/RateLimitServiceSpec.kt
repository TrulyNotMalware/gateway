package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.InMemoryModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class RateLimitServiceSpec :
    BehaviorSpec({

        given("RateLimitService 가 InMemoryModule 위에서 동작할 때") {
            `when`("limit 이하로 요청하면") {
                val svc = RateLimitService(InMemoryModule())
                val result = svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60)
                then("allowed=true, remaining 이 남는다") {
                    result.allowed shouldBe true
                    result.remaining shouldBeGreaterThanOrEqual 0L
                }
            }

            `when`("limit 을 초과하는 횟수만큼 같은 키로 요청하면") {
                val svc = RateLimitService(InMemoryModule())
                repeat(5) { svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60) }
                val over = svc.checkIpRateLimit("1.2.3.4", maxRequests = 5, windowSeconds = 60)
                then("allowed=false 반환") {
                    over.allowed shouldBe false
                }
            }

            `when`("checkMultipleRateLimits 에서 가장 적게 남은 카운터가 결과를 결정한다") {
                val mod = InMemoryModule()
                val svc = RateLimitService(mod)
                // user 카운터를 미리 1로 올려둠. user limit=2 라면 남은 = 1 으로 가장 작아야 함.
                svc.checkUserRateLimit("alice", maxRequests = 2, windowSeconds = 60)
                val result =
                    svc.checkMultipleRateLimits(
                        ip = "1.2.3.4",
                        userId = "alice",
                        apiKey = null,
                        endpoint = null,
                        limits =
                            RateLimitConfig(
                                ipMaxRequests = 1000,
                                userMaxRequests = 2,
                                apiKeyMaxRequests = 1000,
                                windowSeconds = 60,
                            ),
                    )
                then("user 카운터 기준이 더 빠듯해서 remaining 이 작다") {
                    result.allowed shouldBe true
                    // user 는 이제 2 회 사용. limit=2 → remaining=0
                    result.remaining shouldBe 0L
                }
            }

            `when`("identifier 가 모두 null 이면") {
                val svc = RateLimitService(InMemoryModule())
                val result = svc.checkMultipleRateLimits(null, null, null, null)
                then("기본 allowed 반환") {
                    result.allowed shouldBe true
                }
            }
        }
    })
