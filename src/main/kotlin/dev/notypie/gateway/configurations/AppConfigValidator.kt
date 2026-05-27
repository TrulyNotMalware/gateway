package dev.notypie.gateway.configurations

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Fail-fast on invalid AppConfig combinations at startup.
 *
 * Example: `blacklist.storage-mode=redis` + `redis.mode=none` — Redis is required yet disabled.
 * Such a combination would silently fall back to in-memory storage and lose multi-pod synchronization.
 */
@Component
class AppConfigValidator(
    private val appConfig: AppConfig,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun validate() {
        val redisMode = appConfig.redis.mode
        val storageMode = appConfig.blacklist.storageMode

        if (storageMode == StorageMode.REDIS && redisMode == RedisMode.NONE) {
            throw IllegalStateException(
                "Invalid configuration: app.config.blacklist.storage-mode=REDIS requires " +
                    "app.config.redis.mode to be STANDALONE or CLUSTER (current: NONE). " +
                    "Leaving it as is would silently fall back to in-memory storage and split the " +
                    "blacklist across pods.",
            )
        }

        logger.info {
            "AppConfig validated — redis.mode=$redisMode, blacklist.storage-mode=$storageMode, " +
                "security(enabled blacklist=${appConfig.security.enableBlacklist}, " +
                "rateLimit=${appConfig.security.enableRateLimit})"
        }
    }
}
