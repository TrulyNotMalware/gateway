package dev.notypie.gateway.configurations

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * AppConfig 의 invalid 조합을 부팅 시점에 fail-fast 한다.
 *
 * 예: `blacklist.storage-mode=redis` + `redis.mode=none` 처럼 Redis 를 요구하면서도 Redis 가 꺼진 경우.
 * 이 조합은 in-memory fallback 으로 조용히 기동되어 multi-pod 동기화 보장이 사라지는 위험이 있다.
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
                    "이대로 두면 in-memory fallback 으로 기동되어 multi-pod 환경에서 blacklist 가 분리됩니다.",
            )
        }

        logger.info {
            "AppConfig validated — redis.mode=$redisMode, blacklist.storage-mode=$storageMode, " +
                "security(enabled blacklist=${appConfig.security.enableBlacklist}, " +
                "rateLimit=${appConfig.security.enableRateLimit})"
        }
    }
}
