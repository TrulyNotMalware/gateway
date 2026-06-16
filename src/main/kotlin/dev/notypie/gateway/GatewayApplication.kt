package dev.notypie.gateway

import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.redisson.spring.starter.RedissonAutoConfigurationV4
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Redisson 4.x registers both RedissonAutoConfigurationV2 and RedissonAutoConfigurationV4.
 * V2 references Spring Boot's (removed) `data.redis.RedisProperties` and fails on Spring Boot 4.
 * V4's RedissonClient is `@ConditionalOnMissingBean` so it backs off when we register our own,
 * but in Redis-disabled / in-memory modes it would still spin up Redisson + DataRedis beans from
 * the default `spring.data.redis` settings. We register Redisson beans explicitly in
 * [dev.notypie.gateway.configurations.RedisConfiguration] /
 * [dev.notypie.gateway.configurations.RedisClusterConfiguration], so both auto-configs are excluded.
 */
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = [RedissonAutoConfigurationV2::class, RedissonAutoConfigurationV4::class])
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
