package dev.notypie.gateway.modules.redis

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.redisson.api.RScript
import org.redisson.api.RedissonReactiveClient
import org.redisson.api.options.KeysScanParams
import org.redisson.client.codec.LongCodec
import java.time.Duration

val logger = KotlinLogging.logger { }

class ReactiveRedissonClientModule(
    val client: RedissonReactiveClient,
) : RedisModule {
    private fun bucket(key: String) = client.getBucket<String>(key)

    override suspend fun set(key: String, value: String, ttlSeconds: Long?): Boolean =
        runCatching {
            val op =
                ttlSeconds?.let { bucket(key).set(value, Duration.ofSeconds(it)) }
                    ?: bucket(key).set(value)
            op.awaitFirstOrNull()
        }.onFailure { ex -> logger.error { "Failed to set key=$key ttlSeconds=$ttlSeconds, exception=${ex.message}" } }
            .isSuccess

    override suspend fun get(key: String): String? =
        runCatching {
            bucket(key).get().awaitFirstOrNull()
        }.onFailure { ex ->
            logger.error { "Failed to get key=$key, exception=${ex.message}" }
        }.getOrNull()

    override suspend fun exists(key: String): Boolean =
        runCatching {
            bucket(key).isExists().awaitSingle()
        }.onFailure { ex ->
            logger.error { "Failed to check exists key=$key, exception=${ex.message}" }
        }.getOrElse { false }

    override suspend fun delete(key: String): Boolean =
        runCatching {
            bucket(key).delete().awaitSingle()
        }.onFailure { ex ->
            logger.error { "Failed to delete key=$key, exception=${ex.message}" }
        }.getOrElse { false }

    override suspend fun getKeysByPattern(pattern: String): Set<String> =
        runCatching {
            client.keys
                .getKeys(KeysScanParams().pattern(pattern))
                .collectList()
                .awaitSingle()
                .toSet()
        }.onFailure { ex ->
            logger.error { "Failed to get keys by pattern=$pattern, exception=${ex.message}" }
        }.getOrElse { emptySet() }

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
                    RScript.ReturnType.INTEGER,
                    listOf(key),
                    count.toString(),
                    ttlSeconds.toString(),
                ).awaitSingle()
        }.onFailure { ex ->
            logger.error { "Failed to increment key=$key count=$count, exception=${ex.message}" }
        }.getOrElse { 0L }

    override suspend fun remainingTtl(key: String): Long =
        runCatching {
            val atomicLong = client.getAtomicLong(key)
            atomicLong.remainTimeToLive().awaitSingle() / 1000
        }.onFailure { ex ->
            logger.error { "Failed to get remaining TTL key=$key, exception=${ex.message}" }
        }.getOrElse { -2L }
}
