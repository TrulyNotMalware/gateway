package dev.notypie.gateway.configurations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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

const val APP_CONFIG_PROPERTIES_PREFIX = "app.config"

@ConfigurationProperties(prefix = APP_CONFIG_PROPERTIES_PREFIX)
data class AppConfig(
    val blacklist: Blacklist = Blacklist(),
    val redis: Redis = Redis(),
) {
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
    COMMANDS_TIMEOUT_DETECTOR(type = "FailedCommandsTimeoutDetector", detector = FailedConnectionDetector()),
    COMMANDS_DETECTOR(type = "FailedCommandsDetector", detector = FailedCommandsDetector()),
}

@Configuration
class JacksonConfiguration {
    @Bean
    @Primary
    fun objectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModules(Jdk8Module(), JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
