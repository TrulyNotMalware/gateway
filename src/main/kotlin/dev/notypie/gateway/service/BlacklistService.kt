package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.RedisModule
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service

@Service
class BlacklistService(
    private val redisModule: RedisModule,
) {
    companion object {
        private const val BLACKLIST_KEY_PREFIX = "blacklist:"
        private const val IP_BLACKLIST_KEY = "${BLACKLIST_KEY_PREFIX}ip:"
        private const val USER_BLACKLIST_KEY = "${BLACKLIST_KEY_PREFIX}user:"
        private const val API_KEY_BLACKLIST_KEY = "${BLACKLIST_KEY_PREFIX}api_key:"

        /** Hard ceiling on an admin listing so one call cannot walk an unbounded keyspace. */
        const val MAX_LIST_LIMIT = 500
    }

    /**
     * Key namespace per type. These prefixes are a wire format: entries written by an earlier
     * build (or by hand) must keep resolving, so do not rename them.
     */
    fun prefixOf(type: BlacklistType): String =
        when (type) {
            BlacklistType.IP -> IP_BLACKLIST_KEY
            BlacklistType.USER -> USER_BLACKLIST_KEY
            BlacklistType.API_KEY -> API_KEY_BLACKLIST_KEY
        }

    private fun keyOf(type: BlacklistType, value: String) = "${prefixOf(type)}$value"

    suspend fun addIpToBlacklist(ip: String, ttlSeconds: Long? = null) =
        redisModule.set(keyOf(BlacklistType.IP, ip), "1", ttlSeconds)

    suspend fun addUserToBlacklist(userId: String, reason: String, ttlSeconds: Long? = null) =
        redisModule.set(keyOf(BlacklistType.USER, userId), reason, ttlSeconds)

    suspend fun addApiKeyToBlacklist(apiKey: String, reason: String, ttlSeconds: Long? = null) =
        redisModule.set(keyOf(BlacklistType.API_KEY, apiKey), reason, ttlSeconds)

    /** Uniform write path used by the admin endpoint; the typed helpers above stay for callers. */
    suspend fun add(
        type: BlacklistType,
        value: String,
        reason: String,
        ttlSeconds: Long? = null,
    ): Boolean = redisModule.set(keyOf(type, value), reason, ttlSeconds)

    /** @return true when an entry existed and was removed. */
    suspend fun remove(type: BlacklistType, value: String): Boolean = redisModule.delete(keyOf(type, value))

    /**
     * Admin listing. SCAN-backed and bounded — never call this from the request path.
     * Returns the bare values with the key prefix stripped.
     */
    suspend fun list(type: BlacklistType, limit: Int): List<Entry> {
        val prefix = prefixOf(type)
        return redisModule
            .scanKeys("$prefix*", limit.coerceIn(1, MAX_LIST_LIMIT))
            .map { key ->
                val value = key.removePrefix(prefix)
                Entry(
                    value = value,
                    reason = redisModule.get(key),
                    ttlSeconds = redisModule.remainingTtl(key),
                )
            }
    }

    suspend fun isBlacklisted(type: BlacklistType, value: String): Boolean = redisModule.exists(keyOf(type, value))

    /**
     * [apiKey] must already be allowlist-validated by the caller — an arbitrary client-supplied
     * header value has no business creating Redis lookups.
     */
    suspend fun isAnyBlacklisted(ip: String, userId: String?, apiKey: String? = null): Boolean =
        coroutineScope {
            val checks = mutableListOf<Deferred<Boolean>>()

            ip.let { checks.add(async { isBlacklisted(BlacklistType.IP, it) }) }
            userId?.let { checks.add(async { isBlacklisted(BlacklistType.USER, it) }) }
            apiKey?.let { checks.add(async { isBlacklisted(BlacklistType.API_KEY, it) }) }

            if (checks.isEmpty()) {
                false
            } else {
                checks.awaitAll().any { it }
            }
        }

    /**
     * One blacklist row as the admin endpoint reports it.
     * `ttlSeconds` follows Redis conventions: -1 = no expiry, -2 = key missing.
     */
    data class Entry(
        val value: String,
        val reason: String?,
        val ttlSeconds: Long,
    )
}

enum class BlacklistType {
    IP,
    USER,
    API_KEY,
}
