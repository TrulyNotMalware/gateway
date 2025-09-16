package dev.notypie.gateway.configurations

import com.fasterxml.jackson.databind.ObjectMapper
import dev.notypie.gateway.modules.redis.InMemoryModule
import dev.notypie.gateway.modules.redis.ReactiveRedissonClientModule
import dev.notypie.gateway.modules.redis.RedisModule
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.api.RedissonReactiveClient
import org.redisson.codec.JsonJacksonCodec
import org.redisson.config.Config
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

@Configuration
@Conditional(OnRedisRequired::class, OnRedisCluster::class)
class RedisClusterConfiguration(
    val appConfig: AppConfig,
    val objectMapper: ObjectMapper,
) {
    @Bean(destroyMethod = "shutdown")
    fun redissonClusterClient(): RedissonClient =
        Redisson.create(
            Config().apply {
                useClusterServers().apply {
                    // Base
                    appConfig.redis.cluster.nodes
                        .joinToString(",") { it }
                        .also { addNodeAddress(it) }
                    password = appConfig.redis.password
                    connectTimeout = appConfig.redis.connectTimeout
                    timeout = appConfig.redis.timeout
                    retryAttempts = appConfig.redis.retryAttempts

                    // Cluster configuration
                    scanInterval = appConfig.redis.cluster.scanInterval
                    readMode = appConfig.redis.cluster.readMode
                    subscriptionMode = appConfig.redis.cluster.subscriptionMode

                    // connection pool setup
                    masterConnectionMinimumIdleSize = appConfig.redis.cluster.masterConnectionMinimumIdleSize
                    masterConnectionPoolSize = appConfig.redis.cluster.masterConnectionPoolSize
                    slaveConnectionMinimumIdleSize = appConfig.redis.cluster.slaveConnectionMinimumIdleSize
                    slaveConnectionPoolSize = appConfig.redis.cluster.slaveConnectionPoolSize

                    // on failed
                    failedSlaveReconnectionInterval = appConfig.redis.cluster.failedSlaveReconnectionInterval
                    failedSlaveNodeDetector = appConfig.redis.cluster.failedNodeDetector.detector

                    isKeepAlive = true
                    isTcpNoDelay = true
                }
                codec = JsonJacksonCodec(objectMapper)
                // threads
                threads = appConfig.redis.threads
                nettyThreads = appConfig.redis.nettyThreads
                transportMode = appConfig.redis.transportMode
            },
        )

    @Bean
    fun redissonClusterReactiveClient(redissonClusterClient: RedissonClient): RedissonReactiveClient =
        redissonClusterClient.reactive()

    @Bean
    fun redisModule(redissonClusterReactiveClient: RedissonReactiveClient): RedisModule =
        ReactiveRedissonClientModule(client = redissonClusterReactiveClient)
}

@Configuration
@Conditional(OnRedisRequired::class, OnRedisStandalone::class)
class RedisConfiguration(
    val appConfig: AppConfig,
    val objectMapper: ObjectMapper,
) {
    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient =
        Redisson.create(
            Config().apply {
                useSingleServer().apply {
                    // Base
                    address = "redis://${appConfig.redis.host}:${appConfig.redis.port}"
                    password = appConfig.redis.password
                    connectTimeout = appConfig.redis.connectTimeout
                    timeout = appConfig.redis.timeout
                    retryAttempts = appConfig.redis.retryAttempts
                    database = appConfig.redis.standaloneDatabase

                    // connection pool setup
                    connectionPoolSize = appConfig.redis.standaloneConnectionPoolSize
                    connectionMinimumIdleSize = appConfig.redis.standaloneConnectionMinimumIdleSize

                    isKeepAlive = true
                    isTcpNoDelay = true
                }
                codec = JsonJacksonCodec(objectMapper)

                // threads
                threads = appConfig.redis.threads
                nettyThreads = appConfig.redis.nettyThreads
                transportMode = appConfig.redis.transportMode
            },
        )

    @Bean
    fun redissonReactiveClient(redissonClient: RedissonClient): RedissonReactiveClient = redissonClient.reactive()

    @Bean
    fun redisModule(redissonReactiveClient: RedissonReactiveClient): RedisModule =
        ReactiveRedissonClientModule(client = redissonReactiveClient)
}

@Configuration
@Conditional(OnDisableRedis::class)
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
class DisableRedis

@Configuration
class NoRedisConfiguration {
    @Bean
    @ConditionalOnMissingBean(RedisModule::class)
    fun redisModule(): RedisModule = InMemoryModule()
}
