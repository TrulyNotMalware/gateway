package dev.notypie.gateway.modules.redis

import dev.notypie.gateway.configurations.RedisFailureMode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.redisson.api.RScript
import org.redisson.api.RedissonReactiveClient
import org.redisson.api.options.KeysScanOptions
import org.redisson.client.codec.LongCodec
import java.time.Duration

val logger = KotlinLogging.logger { }

class ReactiveRedissonClientModule(
    val client: RedissonReactiveClient,
    private val redisFailureMode: RedisFailureMode = RedisFailureMode.FAIL_OPEN,
    private val inMemoryFallback: InMemoryRateLimitFallback? = null,
    // Optional so the class stays constructible in tests without a registry.
    private val meterRegistry: MeterRegistry? = null,
) : RedisModule {
    init {
        // Configuration error rather than a runtime NPE — surface it at boot so a misconfigured
        // deployment fails fast in startup logs instead of the first Redis hiccup.
        require(redisFailureMode != RedisFailureMode.HYBRID_IN_MEMORY || inMemoryFallback != null) {
            "redisFailureMode=HYBRID_IN_MEMORY requires an InMemoryRateLimitFallback to be wired."
        }
    }

    private fun bucket(key: String) = client.getBucket<String>(key)

    override suspend fun set(key: String, value: String, ttlSeconds: Long?): Boolean =
        runCatching {
            val op =
                ttlSeconds?.let { bucket(key).set(value, Duration.ofSeconds(it)) }
                    ?: bucket(key).set(value)
            op.awaitFirstOrNull()
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to set key=$key ttlSeconds=$ttlSeconds, exception=${ex.message}" }
            }.isSuccess

    override suspend fun get(key: String): String? =
        runCatching {
            bucket(key).get().awaitFirstOrNull()
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to get key=$key, exception=${ex.message}" }
            }.getOrNull()

    // Deliberately NOT swallowed: `exists` backs the blacklist check. Returning a
    // fabricated `false` on Redis failure would silently disable the blacklist
    // regardless of redisFailureMode. Propagating lets SecurityFilter's
    // handleSecurityCheckFailure apply the operator's configured stance instead
    // (FAIL_CLOSED → deny, FAIL_OPEN/HYBRID → allow).
    override suspend fun exists(key: String): Boolean =
        runCatching {
            bucket(key).isExists().awaitSingle()
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to check exists key=$key, exception=${ex.message}" }
            }.getOrThrow()

    override suspend fun delete(key: String): Boolean =
        runCatching {
            bucket(key).delete().awaitSingle()
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to delete key=$key, exception=${ex.message}" }
            }.getOrElse { false }

    // Lua script ensures INCRBY + EXPIRE are atomic: the TTL is set only on the
    // first increment of a window, and a crash between the two operations cannot
    // leave the key without a TTL.
    override suspend fun increment(key: String, count: Long, ttlSeconds: Long): Long =
        runCatching {
            client
                .getScript(LongCodec.INSTANCE)
                .eval<Long>(
                    RScript.Mode.READ_WRITE,
                    """
                    local v = redis.call('INCRBY', KEYS[1], ARGV[1])
                    if v == tonumber(ARGV[1]) then
                        redis.call('EXPIRE', KEYS[1], ARGV[2])
                    end
                    return v
                    """.trimIndent(),
                    RScript.ReturnType.LONG,
                    listOf(key),
                    count.toString(),
                    ttlSeconds.toString(),
                ).awaitSingle()
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to increment key=$key count=$count, exception=${ex.message}" }
            }.getOrElse {
                // Without this counter a degraded Redis is invisible: HYBRID silently absorbs the
                // failure and the request still succeeds, so nothing else signals the outage.
                countRedisFailure("increment")
                when (redisFailureMode) {
                    RedisFailureMode.FAIL_OPEN -> 0L
                    RedisFailureMode.FAIL_CLOSED -> Long.MAX_VALUE
                    RedisFailureMode.HYBRID_IN_MEMORY -> inMemoryFallback!!.increment(key, count, ttlSeconds)
                }
            }

    override suspend fun remainingTtl(key: String): Long =
        runCatching {
            val atomicLong = client.getAtomicLong(key)
            atomicLong.remainTimeToLive().awaitSingle() / 1000
        }.rethrowCancellation()
            .onFailure { ex ->
                logger.error { "Failed to get remaining TTL key=$key, exception=${ex.message}" }
            }.getOrElse {
                countRedisFailure("remainingTtl")
                // FAIL_OPEN / FAIL_CLOSED both surface -2 (key missing) so the response header
                // shows "no info" rather than a fabricated TTL — only HYBRID has real per-pod
                // window state to read from.
                when (redisFailureMode) {
                    RedisFailureMode.FAIL_OPEN, RedisFailureMode.FAIL_CLOSED -> -2L
                    RedisFailureMode.HYBRID_IN_MEMORY -> inMemoryFallback!!.remainingTtl(key)
                }
            }

    // SCAN-based (Redisson iterates every master on a cluster); never KEYS. Admin path only —
    // the request path must not scan. `take` bounds the walk so a large keyspace cannot stall
    // the caller.
    override suspend fun scanKeys(pattern: String, limit: Int): List<String> =
        runCatching {
            // getKeysByPattern(String) is deprecated in Redisson 4.x; KeysScanOptions carries the
            // limit into the SCAN itself instead of over-fetching and truncating client-side.
            client.keys
                .getKeys(KeysScanOptions.defaults().pattern(pattern).limit(limit))
                .collectList()
                .awaitSingle()
        }.rethrowCancellation()
            .onFailure { ex ->
                countRedisFailure("scanKeys")
                logger.error { "Failed to scan keys pattern=$pattern, exception=${ex.message}" }
                // Deliberately NOT swallowed. Returning an empty list would render a Redis outage
                // as `count: 0`, indistinguishable from a genuinely empty blacklist — an operator
                // would read "nothing is blocked" when the truth is "we cannot tell".
            }.getOrThrow()

    /**
     * `runCatching` catches `Throwable`, which includes `CancellationException`. Without this,
     * cancelling an in-flight Redis await — SecurityFilter's `withTimeout` firing, or the client
     * disconnecting — would be misread as a Redis outage: the failure branch would increment the
     * in-memory fallback counter and emit `gateway.redis.operation.failures`, and the coroutine
     * would keep running after it was cancelled. Cancellation is not a Redis failure.
     */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { if (it is CancellationException) throw it }

    private fun countRedisFailure(operation: String) {
        meterRegistry
            ?.counter(
                "gateway.redis.operation.failures",
                "operation",
                operation,
                "failureMode",
                redisFailureMode.name,
            )?.increment()
    }
}
