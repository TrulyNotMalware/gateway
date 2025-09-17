package dev.notypie.gateway.modules.redis

interface RedisModule {
    suspend fun set(key: String, value: String, ttlSeconds: Long? = null): Boolean

    suspend fun get(key: String): String?

    suspend fun exists(key: String): Boolean

    suspend fun delete(key: String): Boolean

    suspend fun getKeysByPattern(pattern: String): Set<String>

    suspend fun increment(key: String, count: Long, ttlSeconds: Long = 60): Long

    suspend fun remainingTtl(key: String): Long
}
