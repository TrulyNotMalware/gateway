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
    }

    suspend fun addIpToBlacklist(ip: String, ttlSeconds: Long? = null) =
        redisModule.set("$IP_BLACKLIST_KEY$ip", "1", ttlSeconds)

    suspend fun addUserToBlacklist(userId: String, reason: String, ttlSeconds: Long? = null) =
        redisModule.set("$USER_BLACKLIST_KEY$userId", reason, ttlSeconds)

    suspend fun addApiKeyToBlacklist(apiKey: String, reason: String, ttlSeconds: Long? = null) =
        redisModule.set("$API_KEY_BLACKLIST_KEY$apiKey", reason, ttlSeconds)

    suspend fun isBlacklisted(type: BlacklistType, value: String): Boolean {
        val key =
            when (type) {
                BlacklistType.IP -> "$IP_BLACKLIST_KEY$value"
                BlacklistType.USER -> "$USER_BLACKLIST_KEY$value"
                BlacklistType.API_KEY -> "$API_KEY_BLACKLIST_KEY$value"
            }
        return redisModule.exists(key)
    }

    suspend fun isAnyBlacklisted(ip: String?, userId: String?, apiKey: String?): Boolean =
        coroutineScope {
            val checks = mutableListOf<Deferred<Boolean>>()

            ip?.let { checks.add(async { isBlacklisted(BlacklistType.IP, it) }) }
            userId?.let { checks.add(async { isBlacklisted(BlacklistType.USER, it) }) }
            apiKey?.let { checks.add(async { isBlacklisted(BlacklistType.API_KEY, it) }) }

            if (checks.isEmpty()) {
                false
            } else {
                checks.awaitAll().any { it }
            }
        }

    suspend fun getBlacklistByType(type: BlacklistType): Set<String> {
        val pattern =
            when (type) {
                BlacklistType.IP -> "${IP_BLACKLIST_KEY}*"
                BlacklistType.USER -> "${USER_BLACKLIST_KEY}*"
                BlacklistType.API_KEY -> "${API_KEY_BLACKLIST_KEY}*"
            }
        return redisModule.getKeysByPattern(pattern)
    }
}

enum class BlacklistType {
    IP,
    USER,
    API_KEY,
}
