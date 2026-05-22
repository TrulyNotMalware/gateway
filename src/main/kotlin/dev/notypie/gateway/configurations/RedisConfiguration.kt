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
                    // addNodeAddress 는 vararg<String> — 노드 URI 를 개별 인수로 넘겨야 한다.
                    // (이전 코드는 joinToString 으로 단일 문자열을 만들어 넘겨 MalformedURL 발생)
                    addNodeAddress(
                        *appConfig.redis.cluster.nodes
                            .toTypedArray(),
                    )
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

// Redisson auto config 는 GatewayApplication 레벨에서 항상 exclude 됨 (Spring Boot 4 호환성). 본 클래스는 보존만 함.
@Configuration
@Conditional(OnDisableRedis::class)
class DisableRedis

/**
 * Redis 가 비활성화(NONE)이거나 blacklist storage 가 IN_MEMORY 일 때만 in-memory RedisModule 을 등록한다.
 *
 * 핵심: @Conditional(OnRedisDisabledOrInMemoryStorage::class) 가 없으면 RedisConfiguration/RedisClusterConfiguration
 *      이 활성화돼서 `redisModule` 빈을 등록한 상태에서도 본 클래스가 같이 로딩되어 bean override 충돌이 발생한다.
 *      (Spring Boot 4 기본값: spring.main.allow-bean-definition-overriding=false)
 *      @ConditionalOnMissingBean 은 Spring 의 평가 순서상 동일 라운드에서 등록되는 빈에 대해 신뢰할 수 없다.
 */
@Configuration
@Conditional(OnInMemoryRedisModule::class)
class NoRedisConfiguration {
    @Bean
    fun redisModule(): RedisModule = InMemoryModule()
}
