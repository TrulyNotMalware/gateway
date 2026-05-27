package dev.notypie.gateway

import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Redisson's RedissonAutoConfigurationV2 references Spring Boot's (removed)
 * `data.redis.RedisProperties` and fails on Spring Boot 4 with ClassNotFoundException.
 * Redisson beans are registered directly in [dev.notypie.gateway.configurations.RedisConfiguration] /
 * [dev.notypie.gateway.configurations.RedisClusterConfiguration], so the auto-config is always excluded.
 */
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = [RedissonAutoConfigurationV2::class])
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
