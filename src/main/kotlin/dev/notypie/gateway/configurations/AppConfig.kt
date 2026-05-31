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
    val jwt: Jwt = Jwt(),
) {
    /**
     * Multi-issuer JWT verification config.
     *
     * The gateway is purely a verifier — each backend service signs its own tokens with its
     * own RSA private key and exposes a `/.well-known/jwks.json`. The gateway picks the
     * right public key by routing on the token's `iss` claim. Adding a new backend means
     * adding one entry to `issuers` (no code change, regardless of the backend's language).
     *
     * Wire from k8s ConfigMap with indexed env vars (Spring Boot relaxed binding maps
     * kebab-case `jwks-uri` to `JWKS_URI`):
     *   APP_CONFIG_JWT_ISSUERS_0_ISSUER=blog-be
     *   APP_CONFIG_JWT_ISSUERS_0_JWKS_URI=http://blog-svc.blog.svc.cluster.local:8080/.well-known/jwks.json
     *   APP_CONFIG_JWT_ISSUERS_1_ISSUER=file-be
     *   APP_CONFIG_JWT_ISSUERS_1_JWKS_URI=http://file-svc.file.svc.cluster.local:8080/.well-known/jwks.json
     */
    data class Jwt(
        val issuers: List<IssuerEntry> = emptyList(),
    ) {
        /**
         * One verifier per backend service. `audience` (optional but recommended) is the
         * `aud` claim the issuer must stamp on its tokens. When set, the gateway rejects
         * tokens missing it — catches a leaked token being replayed against a verifier it
         * was never minted for. Per-route audience binding is a separate concern; this is
         * the issuer-level contract.
         */
        data class IssuerEntry(
            val issuer: String = "",
            val jwksUri: String = "",
            val audience: String = "",
        )
    }

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
                "X-Gateway-Auth",
            ),
        val gatewaySharedSecret: String = "",
        // Stance when Redis (the source of truth for RateLimit counters) is unreachable.
        // FAIL_OPEN -> increment returns 0 (no throttle); FAIL_CLOSED -> Long.MAX_VALUE
        // (deny all); HYBRID_IN_MEMORY -> fall through to per-pod ConcurrentHashMap counter
        // (state not shared across pods, but single-source bursts still throttle locally).
        val redisFailureMode: RedisFailureMode = RedisFailureMode.FAIL_OPEN,
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

enum class RedisFailureMode {
    FAIL_OPEN,
    FAIL_CLOSED,
    HYBRID_IN_MEMORY,
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
     * tools.jackson (Jackson 3.x) JsonMapper consumed by application code (e.g. SecurityFilter).
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
     * Redisson's `JsonJacksonCodec` requires `com.fasterxml.jackson.databind.ObjectMapper` directly.
     * Register it explicitly in case Spring Boot 4 no longer auto-configures the classic Jackson stack.
     * (When Redis is disabled this bean is never injected, so the cost is effectively zero.)
     */
    @Bean
    fun classicObjectMapper(): ClassicObjectMapper = ClassicObjectMapper()
}
