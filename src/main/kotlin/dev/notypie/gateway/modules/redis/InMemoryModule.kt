package dev.notypie.gateway.modules.redis

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class InMemoryModule :
    RedisModule,
    CoroutineScope,
    DisposableBean {
    private data class CacheEntry(
        val value: String,
        val expiresAtMillis: Long? = null,
    ) {
        val isExpired: Boolean
            get() = expiresAtMillis?.let { System.currentTimeMillis() > it } ?: false
    }

    companion object {
        const val CLEANING_INTERVAL_MINUTES = 5L
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    @PostConstruct
    fun startCleanupTask() {
        launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(CLEANING_INTERVAL_MINUTES))
                cleanupExpiredEntries()
            }
        }
    }

    override fun destroy() {
        job.cancel()
        cache.clear()
    }

    private fun cleanupExpiredEntries() {
        val now = System.currentTimeMillis()
        val expiredEntries =
            cache.filterValues { entry ->
                entry.expiresAtMillis?.let { it <= now } ?: false
            }
        expiredEntries.keys.forEach { key ->
            cache.remove(key)
        }
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

    override suspend fun increment(key: String, count: Long, ttlSeconds: Long): Long =
        cache
            .compute(key) { _, old ->
                val expiresAt =
                    old?.expiresAtMillis ?: (System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ttlSeconds))
                val oldValue = old?.value?.toLongOrNull() ?: 0L
                val newValue = oldValue + count
                CacheEntry(newValue.toString(), expiresAt)
            }?.value
            ?.toLongOrNull() ?: 0L

    override suspend fun remainingTtl(key: String): Long {
        val entry = resolve(key) ?: return -2
        val expiresAt = entry.expiresAtMillis ?: return -1
        val ttlMillis = expiresAt - System.currentTimeMillis()
        return if (ttlMillis <= 0) {
            cache.remove(key, entry)
            -2
        } else {
            TimeUnit.MILLISECONDS.toSeconds(ttlMillis)
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
