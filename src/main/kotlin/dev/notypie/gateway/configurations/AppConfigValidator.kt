package dev.notypie.gateway.configurations

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fail-fast on invalid AppConfig combinations at startup.
 *
 * Example: `blacklist.storage-mode=redis` + `redis.mode=none` — Redis is required yet disabled.
 * Such a combination would silently fall back to in-memory storage and lose multi-pod synchronization.
 *
 * Also enforces prod-only invariants — e.g. an empty `gateway-shared-secret` silently disables
 * [dev.notypie.gateway.filters.global.GatewayHopHeaderFilter], which would let any pod that
 * reaches blog_be directly forge the hop and inherit admin trust.
 */
@Component
class AppConfigValidator(
    private val appConfig: AppConfig,
    private val environment: Environment,
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
                    "Leaving it as is would silently fall back to in-memory storage and split the " +
                    "blacklist across pods.",
            )
        }

        if (isProdProfile()) {
            checkProdGatewaySharedSecret()
            checkProdJwtIssuers()
        }

        logger.info {
            "AppConfig validated — redis.mode=$redisMode, blacklist.storage-mode=$storageMode, " +
                "security(enabled blacklist=${appConfig.security.enableBlacklist}, " +
                "rateLimit=${appConfig.security.enableRateLimit})"
        }
    }

    private fun isProdProfile(): Boolean = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }

    private fun checkProdGatewaySharedSecret() {
        // Trim before measuring so padded values like `"  abc  "` don't disguise a
        // too-short secret. Mirrors blog_be's `GATEWAY_SHARED_SECRET.strip()` check
        // in `_check_prod_gateway_secret`.
        val secret = appConfig.security.gatewaySharedSecret.trim()
        if (secret.isEmpty()) {
            throw IllegalStateException(
                "Invalid configuration: app.config.security.gateway-shared-secret must be set in prod. " +
                    "Empty disables GatewayHopHeaderFilter — backend would no longer be able to prove " +
                    "the request traversed this gateway.",
            )
        }
        if (secret.length < MIN_GATEWAY_SHARED_SECRET_LEN) {
            throw IllegalStateException(
                "Invalid configuration: app.config.security.gateway-shared-secret must be at least " +
                    "$MIN_GATEWAY_SHARED_SECRET_LEN chars in prod (got ${secret.length}).",
            )
        }
    }

    private fun checkProdJwtIssuers() {
        val issuers = appConfig.jwt.issuers
        if (issuers.isEmpty()) {
            throw IllegalStateException(
                "Invalid configuration: app.config.jwt.issuers must contain at least one entry in prod. " +
                    "Wire it via APP_CONFIG_JWT_ISSUERS_0_ISSUER + APP_CONFIG_JWT_ISSUERS_0_JWKS_URI " +
                    "(see gateway/src/main/resources/k8s/dok/configmap.yaml).",
            )
        }
        issuers.forEachIndexed { idx, entry ->
            if (entry.issuer.isBlank() || entry.jwksUri.isBlank()) {
                throw IllegalStateException(
                    "Invalid configuration: app.config.jwt.issuers[$idx] must have both 'issuer' " +
                        "and 'jwks-uri' set (got issuer='${entry.issuer}', jwksUri='${entry.jwksUri}').",
                )
            }
            if (entry.audience.isBlank()) {
                throw IllegalStateException(
                    "Invalid configuration: app.config.jwt.issuers[$idx] (issuer='${entry.issuer}') " +
                        "must declare a non-blank 'audience' in prod. Each backend issuer must stamp " +
                        "tokens with its own `aud` claim so a verifier never accepts a token minted " +
                        "for a different service.",
                )
            }
        }
        val duplicates =
            issuers
                .groupingBy { it.issuer }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            throw IllegalStateException(
                "Invalid configuration: duplicate issuer name(s) in app.config.jwt.issuers: $duplicates. " +
                    "Each issuer must be unique — the resolver routes by `iss` claim.",
            )
        }
    }

    companion object {
        // Mirrors blog_be's `_MIN_GATEWAY_SHARED_SECRET_LEN` in app/core/config/config.py.
        const val MIN_GATEWAY_SHARED_SECRET_LEN = 32
    }
}
