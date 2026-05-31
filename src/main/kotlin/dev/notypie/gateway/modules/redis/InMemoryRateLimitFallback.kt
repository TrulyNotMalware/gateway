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

/**
 * Per-pod ConcurrentHashMap counter used when [AppConfig.Security.redisFailureMode] is
 * `HYBRID_IN_MEMORY` and the Redis call inside [ReactiveRedissonClientModule.increment]
 * throws. State is **not** shared across pods — the effective rate limit is multiplied by
 * the replica count, but a single bursty source still gets throttled inside its own pod.
 *
 * Only the increment + TTL surface is replicated. The full [RedisModule] semantics (set,
 * get, blacklist storage) are intentionally **not** mirrored: blacklist state needs to be
 * shared cluster-wide to mean anything, so falling it back per-pod would be worse than the
 * current `enable-blacklist=true` + Redis-down behaviour. RateLimit counters, by contrast,
 * tolerate split state — each pod sees an independent slice.
 */
class InMemoryRateLimitFallback :
    CoroutineScope,
    DisposableBean {
    private data class Window(
        val count: Long,
        val expiresAtMillis: Long,
    ) {
        val expired: Boolean
            get() = System.currentTimeMillis() >= expiresAtMillis
    }

    private val counters = ConcurrentHashMap<String, Window>()
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    @PostConstruct
    fun startCleanup() {
        launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(CLEANUP_INTERVAL_MIN))
                purgeExpired()
            }
        }
    }

    override fun destroy() {
        job.cancel()
        counters.clear()
    }

    fun increment(key: String, count: Long, ttlSeconds: Long): Long {
        val now = System.currentTimeMillis()
        // Carry over the existing window's deadline so a sustained burst keeps incrementing
        // the same counter until it naturally expires — same semantics as the Redis Lua
        // script (EXPIRE only on first INCR of a window).
        val updated =
            counters.compute(key) { _, prev ->
                if (prev == null || prev.expired) {
                    Window(count = count, expiresAtMillis = now + TimeUnit.SECONDS.toMillis(ttlSeconds))
                } else {
                    prev.copy(count = prev.count + count)
                }
            }!!
        return updated.count
    }

    fun remainingTtl(key: String): Long {
        val w = counters[key] ?: return -2L
        if (w.expired) {
            counters.remove(key, w)
            return -2L
        }
        return TimeUnit.MILLISECONDS.toSeconds(w.expiresAtMillis - System.currentTimeMillis())
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        counters.entries.removeIf { it.value.expiresAtMillis <= now }
    }

    companion object {
        const val CLEANUP_INTERVAL_MIN = 5L
    }
}
