package dev.notypie.gateway.service

import dev.notypie.gateway.modules.redis.RedisModule
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service

@Service
class RateLimitService(
    private val redisModule: RedisModule,
) {
    companion object {
        private const val RATE_LIMIT_KEY_PREFIX = "rate_limit:"
        private const val IP_RATE_LIMIT_KEY = "${RATE_LIMIT_KEY_PREFIX}ip:"
        private const val USER_RATE_LIMIT_KEY = "${RATE_LIMIT_KEY_PREFIX}user:"
        private const val API_KEY_RATE_LIMIT_KEY = "${RATE_LIMIT_KEY_PREFIX}api_key:"
        private const val ENDPOINT_RATE_LIMIT_KEY = "${RATE_LIMIT_KEY_PREFIX}endpoint:"
    }

    suspend fun checkIpRateLimit(ip: String, maxRequests: Long = 1000, windowSeconds: Long = 60): RateLimitResult {
        val key = "$IP_RATE_LIMIT_KEY$ip"
        return checkRateLimit(key, maxRequests, windowSeconds)
    }

    suspend fun checkUserRateLimit(userId: String, maxRequests: Long = 500, windowSeconds: Long = 60): RateLimitResult {
        val key = "$USER_RATE_LIMIT_KEY$userId"
        return checkRateLimit(key, maxRequests, windowSeconds)
    }

    suspend fun checkApiKeyRateLimit(
        apiKey: String,
        maxRequests: Long = 1000,
        windowSeconds: Long = 60,
    ): RateLimitResult {
        val key = "$API_KEY_RATE_LIMIT_KEY$apiKey"
        return checkRateLimit(key, maxRequests, windowSeconds)
    }

    suspend fun checkEndpointRateLimit(
        endpoint: String,
        identifier: String,
        maxRequests: Long = 100,
        windowSeconds: Long = 60,
    ): RateLimitResult {
        val key = "$ENDPOINT_RATE_LIMIT_KEY$endpoint:$identifier"
        return checkRateLimit(key, maxRequests, windowSeconds)
    }

    suspend fun checkMultipleRateLimits(
        ip: String?,
        userId: String?,
        apiKey: String?,
        endpoint: String? = null,
        limits: RateLimitConfig = RateLimitConfig(),
    ): RateLimitResult =
        coroutineScope {
            val checks = mutableListOf<Deferred<RateLimitResult>>()
            ip?.let {
                checks.add(
                    async {
                        checkIpRateLimit(it, limits.ipMaxRequests, limits.windowSeconds)
                    },
                )
            }
            userId?.let {
                checks.add(
                    async {
                        checkUserRateLimit(it, limits.userMaxRequests, limits.windowSeconds)
                    },
                )
            }
            apiKey?.let {
                checks.add(
                    async {
                        checkApiKeyRateLimit(it, limits.apiKeyMaxRequests, limits.windowSeconds)
                    },
                )
            }
            endpoint?.let { ep ->
                val identifier = userId ?: apiKey ?: ip ?: "anonymous"
                checks.add(
                    async {
                        checkEndpointRateLimit(ep, identifier, limits.endpointMaxRequests, limits.windowSeconds)
                    },
                )
            }
            if (checks.isEmpty()) {
                RateLimitResult.allowed(Long.MAX_VALUE, -1)
            } else {
                val results = checks.awaitAll()
                results.minByOrNull { it.remaining } ?: RateLimitResult.allowed(Long.MAX_VALUE, -1)
            }
        }

    private suspend fun checkRateLimit(key: String, maxRequests: Long, windowSeconds: Long): RateLimitResult {
        val currentCount = redisModule.increment(key, 1, windowSeconds)
        val remaining = maxOf(0, maxRequests - currentCount)
        val ttl = redisModule.remainingTtl(key)

        return if (currentCount > maxRequests) {
            RateLimitResult.exceeded(remaining, ttl)
        } else {
            RateLimitResult.allowed(remaining, ttl)
        }
    }
}

data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Long,
    val resetTimeSeconds: Long,
) {
    companion object {
        fun allowed(remaining: Long, resetTimeSeconds: Long) = RateLimitResult(true, remaining, resetTimeSeconds)

        fun exceeded(remaining: Long, resetTimeSeconds: Long) = RateLimitResult(false, remaining, resetTimeSeconds)
    }
}

data class RateLimitConfig(
    val ipMaxRequests: Long = 1000,
    val userMaxRequests: Long = 500,
    val apiKeyMaxRequests: Long = 1000,
    val endpointMaxRequests: Long = 100,
    val windowSeconds: Long = 60,
)

enum class RateLimitType {
    IP,
    USER,
    API_KEY,
}
