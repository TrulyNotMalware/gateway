package dev.notypie.gateway.configurations

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

fun Environment.extractAppConfig(): AppConfig =
    Binder.get(this).bind(APP_CONFIG_PROPERTIES_PREFIX, AppConfig::class.java).orElse(AppConfig())

class OnRedisRequired : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .blacklist.storageMode == StorageMode.REDIS
}

/**
 * A condition that determines if the Redis configuration is set to use a cluster mode.
 * This condition checks the application's configuration and evaluates if the Redis mode is
 * configured as [RedisMode.CLUSTER].
 *
 * Implements the [Condition] interface, which allows creating custom logic to conditionally
 * include components in a Spring application context.
 *
 * The Redis configuration is retrieved from the application's environment by using the
 * extension function `extractAppConfig`.
 */
class OnRedisCluster : Condition {
    /**
     * Checks if the Redis configuration mode is set to CLUSTER in the application configuration.
     *
     * @param context the condition context, providing access to the environment and other resources
     * @param metadata the metadata of the {@link AnnotatedTypeMetadata} class, used for additional condition evaluation
     * @return `true` if the Redis mode is CLUSTER, `false` otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .redis.mode == RedisMode.CLUSTER
}

/**
 * A condition that matches when the Redis mode specified in the application configuration
 * is set to STANDALONE.
 *
 * The condition checks the value of `redis.mode` in the application's configuration, extracted
 * from the environment, and matches if it equals [RedisMode.STANDALONE].
 */
class OnRedisStandalone : Condition {
    /**
     * Evaluates whether the current Redis configuration mode is set to standalone.
     *
     * @param context the condition context providing access to the environment and other resources
     * @param metadata metadata about the class or method being evaluated
     * @return `true` if the Redis mode is standalone, `false` otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .redis.mode == RedisMode.STANDALONE
}
