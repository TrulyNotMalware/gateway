package dev.notypie.gateway.modules.redis

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InMemoryModule : RedisModule {
    private data class CacheEntry(
        val value: String,
        val expiresAtMillis: Long? = null,
    ) {
        val isExpired: Boolean
            get() = expiresAtMillis?.let { System.currentTimeMillis() > it } ?: false
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private fun resolve(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        return if (entry.isExpired) {
            // Remove only if the same entry (avoid races)
            cache.remove(key, entry)
            null
        } else {
            entry
        }
    }

    override suspend fun set(key: String, value: String, ttlSeconds: Long?): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt =
            ttlSeconds
                ?.takeIf { it > 0 }
                ?.let { now + TimeUnit.SECONDS.toMillis(it) }

        cache[key] = CacheEntry(value, expiresAt)
        return true
    }

    override suspend fun getKeysByPattern(pattern: String): Set<String> {
        val regex = pattern.replace("*", ".*").toRegex()
        return cache.keys.filter { regex.matches(it) }.toSet()
    }

    override suspend fun get(key: String): String? = resolve(key)?.value

    override suspend fun exists(key: String): Boolean = resolve(key) != null

    override suspend fun delete(key: String): Boolean = cache.remove(key) != null
}
