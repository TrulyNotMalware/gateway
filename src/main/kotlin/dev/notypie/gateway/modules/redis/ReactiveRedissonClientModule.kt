package dev.notypie.gateway.modules.redis

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.redisson.api.RedissonReactiveClient
import org.redisson.api.options.KeysScanParams
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

    override suspend fun get(key: String): String? = bucket(key).get().awaitFirstOrNull()

    override suspend fun exists(key: String): Boolean = bucket(key).isExists().awaitSingle()

    override suspend fun delete(key: String): Boolean = bucket(key).delete().awaitSingle()

    override suspend fun getKeysByPattern(pattern: String): Set<String> =
        client.keys
            .getKeys(KeysScanParams().pattern(pattern))
            .collectList()
            .awaitSingle()
            .toSet()
}
