package dev.notypie.gateway.configurations

import org.redisson.client.FailedCommandsDetector
import org.redisson.client.FailedConnectionDetector
import org.redisson.client.FailedNodeDetector
import org.redisson.config.ReadMode
import org.redisson.config.SubscriptionMode
import org.redisson.config.TransportMode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.databind.ObjectMapper as ClassicObjectMapper

const val APP_CONFIG_PROPERTIES_PREFIX = "app.config"

@ConfigurationProperties(prefix = APP_CONFIG_PROPERTIES_PREFIX)
data class AppConfig(
    val blacklist: Blacklist = Blacklist(),
    val redis: Redis = Redis(),
    val security: Security = Security(),
) {
    data class Security(
        val timeoutMs: Long = 1000L,
        val enableBlacklist: Boolean = true,
        val enableRateLimit: Boolean = true,
        val ipMaxRequests: Long = 1000L,
        val userMaxRequests: Long = 500L,
        val apiKeyMaxRequests: Long = 1000L,
        val endpointMaxRequests: Long = 100L,
        val windowSeconds: Long = 60L,
        val strippedTrustHeaders: List<String> =
            listOf(
                "X-User-ID",
                "X-API-Key",
                "X-Internal-Auth",
            ),
    )

    data class Blacklist(
        val storageMode: StorageMode = StorageMode.IN_MEMORY,
    )

    data class Redis(
        val mode: RedisMode = RedisMode.NONE,
        val cluster: Cluster = Cluster(),
        val host: String = "127.0.0.1",
        val port: Int = 7001,
        val standaloneConnectionPoolSize: Int = 64,
        val standaloneConnectionMinimumIdleSize: Int = 10,
        val standaloneDatabase: Int = 0,
        val connectTimeout: Int = 10000,
        val timeout: Int = 3000,
        val retryAttempts: Int = 3,
        val password: String = "",
        val threads: Int = 16,
        val nettyThreads: Int = 32,
        val transportMode: TransportMode = TransportMode.NIO,
    )

    data class Cluster(
        val nodes: List<String> = emptyList(),
        val scanInterval: Int = 2000,
        val readMode: ReadMode = ReadMode.SLAVE,
        val subscriptionMode: SubscriptionMode = SubscriptionMode.MASTER,
        val masterConnectionMinimumIdleSize: Int = 10,
        val masterConnectionPoolSize: Int = 64,
        val slaveConnectionMinimumIdleSize: Int = 10,
        val slaveConnectionPoolSize: Int = 64,
        val failedSlaveReconnectionInterval: Int = 3000,
        val failedNodeDetector: FailedNodeDetectorType = FailedNodeDetectorType.CONNECTION_DETECTOR,
    )
}

enum class RedisMode {
    STANDALONE,
    CLUSTER,
    NONE,
}

enum class StorageMode {
    REDIS,
    IN_MEMORY,
}

enum class FailedNodeDetectorType(
    val type: String,
    val detector: FailedNodeDetector,
) {
    CONNECTION_DETECTOR(type = "FailedConnectionDetector", detector = FailedConnectionDetector()),
    COMMANDS_DETECTOR(type = "FailedCommandsDetector", detector = FailedCommandsDetector()),
}

@Configuration
class JacksonConfiguration {
    /**
     * tools.jackson (Jackson 3.x) 기반 JsonMapper. 애플리케이션 코드(SecurityFilter 등)가 사용.
     */
    @Bean
    @Primary
    fun jsonMapper(): JsonMapper =
        JsonMapper
            .builder()
            .findAndAddModules()
            .addModule(
                KotlinModule
                    .Builder()
                    .enable(KotlinFeature.UseJavaDurationConversion)
                    .enable(KotlinFeature.KotlinPropertyNameAsImplicitName)
                    .build(),
            ).enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()

    /**
     * Classic Jackson 2.x ObjectMapper.
     *
     * Redisson 의 `JsonJacksonCodec` 가 `com.fasterxml.jackson.databind.ObjectMapper` 를 직접 요구해서
     * Spring Boot 4 가 classic Jackson autoconfig 를 제공하지 않을 때를 대비해 명시적으로 등록.
     * (Redis 가 활성화되지 않으면 어디서도 주입되지 않으므로 비용 거의 0)
     */
    @Bean
    fun classicObjectMapper(): ClassicObjectMapper = ClassicObjectMapper()
}
