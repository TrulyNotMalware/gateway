package dev.notypie.gateway.modules.redis

interface RedisModule {
    suspend fun set(key: String, value: String, ttlSeconds: Long? = null): Boolean

    suspend fun get(key: String): String?

    suspend fun exists(key: String): Boolean

    suspend fun delete(key: String): Boolean

    suspend fun increment(key: String, count: Long, ttlSeconds: Long = 60): Long

    suspend fun remainingTtl(key: String): Long

    /**
     * Admin-only key lookup, backed by SCAN (never KEYS) so it does not block the server on a
     * large keyspace. Bounded by [limit]; ordering is unspecified and the result may be a
     * partial view of a keyspace that is changing underneath the scan.
     *
     * Only the blacklist admin endpoint uses this — the request path must never scan.
     */
    suspend fun scanKeys(pattern: String, limit: Int): List<String>
}
