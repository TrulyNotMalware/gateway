package dev.notypie.gateway

import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Redisson 의 RedissonAutoConfigurationV2 는 Spring Boot 의 (제거된) `data.redis.RedisProperties` 를 참조해
 * Spring Boot 4 에서 ClassNotFoundException 으로 기동 실패한다.
 * Redisson 빈은 [dev.notypie.gateway.configurations.RedisConfiguration] / [dev.notypie.gateway.configurations.RedisClusterConfiguration]
 * 에서 직접 등록하므로 auto config 는 항상 비활성화한다.
 */
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = [RedissonAutoConfigurationV2::class])
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
