package dev.notypie.gateway.modules.redis

import dev.notypie.gateway.configurations.RedisFailureMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.redisson.api.RAtomicLongReactive
import org.redisson.api.RScript
import org.redisson.api.RScriptReactive
import org.redisson.api.RedissonReactiveClient
import org.redisson.client.codec.Codec
import reactor.core.publisher.Mono
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pins the module's own dispatch around the Redisson client (TTL sentinels, cancellation) with a
 * mocked client. Redis semantics — the Lua script, real expiry — still need a live Redis.
 */
class ReactiveRedissonClientModuleSpec :
    BehaviorSpec({

        given("remainingTtl") {
            fun moduleReturningTtl(millis: Long): ReactiveRedissonClientModule {
                val atomic = mockk<RAtomicLongReactive>()
                every { atomic.remainTimeToLive() } returns Mono.just(millis)
                val client = mockk<RedissonReactiveClient>()
                every { client.getAtomicLong(any<String>()) } returns atomic
                return ReactiveRedissonClientModule(client)
            }

            `when`("Redis reports a missing key (-2) or a key without expiry (-1)") {
                val missing = moduleReturningTtl(-2L).remainingTtl("k")
                val noExpiry = moduleReturningTtl(-1L).remainingTtl("k")
                then("the sentinels are passed through instead of being divided into 0") {
                    missing shouldBe -2L
                    noExpiry shouldBe -1L
                }
            }

            `when`("Redis reports a TTL in milliseconds") {
                val seconds = moduleReturningTtl(42_500L).remainingTtl("k")
                then("it is converted to whole seconds") {
                    seconds shouldBe 42L
                }
            }
        }

        given("increment in HYBRID_IN_MEMORY mode when the client fails with a CancellationException") {
            // An internally cancelled future surfaces as an error while the caller is still active.
            val script = mockk<RScriptReactive>()
            every {
                script.eval<Long>(
                    any<RScript.Mode>(),
                    any<String>(),
                    any<RScript.ReturnType>(),
                    any<List<Any>>(),
                    *anyVararg(),
                )
            } returns Mono.error(CancellationException("cancelled inside the client"))
            val client = mockk<RedissonReactiveClient>()
            every { client.getScript(any<Codec>()) } returns script
            val fallback = InMemoryRateLimitFallback()
            val module = ReactiveRedissonClientModule(client, RedisFailureMode.HYBRID_IN_MEMORY, fallback)

            `when`("the call fails while the coroutine is still active") {
                val count = module.increment("rate_limit:ip:5.6.7.8", 1, 60)
                then("it is a Redis failure: the in-memory fallback counts the request") {
                    count shouldBe 1L
                    fallback.remainingTtl("rate_limit:ip:5.6.7.8") shouldBeGreaterThan 0L
                }
            }
        }

        given("increment in HYBRID_IN_MEMORY mode while Redis does not answer") {
            val script = mockk<RScriptReactive>()
            every {
                script.eval<Long>(
                    any<RScript.Mode>(),
                    any<String>(),
                    any<RScript.ReturnType>(),
                    any<List<Any>>(),
                    *anyVararg(),
                )
            } returns Mono.never()
            val client = mockk<RedissonReactiveClient>()
            every { client.getScript(any<Codec>()) } returns script
            val fallback = InMemoryRateLimitFallback()
            val module = ReactiveRedissonClientModule(client, RedisFailureMode.HYBRID_IN_MEMORY, fallback)

            `when`("the caller's deadline cancels the call") {
                shouldThrow<TimeoutCancellationException> {
                    withTimeout(100) { module.increment("rate_limit:ip:1.2.3.4", 1, 60) }
                }
                then("the cancellation propagates and the in-memory fallback is not touched") {
                    fallback.remainingTtl("rate_limit:ip:1.2.3.4") shouldBe -2L
                }
            }
        }
    })
