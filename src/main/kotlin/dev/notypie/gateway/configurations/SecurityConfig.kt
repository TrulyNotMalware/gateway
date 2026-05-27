package dev.notypie.gateway.configurations

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter
import org.springframework.security.web.server.SecurityWebFilterChain
import javax.crypto.spec.SecretKeySpec

/**
 * Spring Security Resource Server + JWT verification.
 *
 * Verifies the HS256 token issued by blog_be (FastAPI) using the same shared secret.
 * Expected token payload:
 *   - sub : user identifier (ADMIN_USERNAME)
 *   - iss : "blog-be" (or the ConfigMap's JWT_ISSUER)
 *   - exp : expiry
 *   - typ : "access" (refresh tokens are rejected)
 *
 * The verified `sub` is stamped into the X-User-ID header by
 * [dev.notypie.gateway.filters.global.JwtUserIdInjectionFilter] (TrustHeaderStripFilter has already
 * removed any external input).
 *
 * Authorization policy:
 *   - public: GET `/v1/posts/`, `/v1/tags/`, `/v1/search/`, POST `/v1/auth/login`, `/v1/auth/refresh`,
 *             `/fallback/`, `/actuator/` (mgmt port is separate but listed for safety)
 *   - everything else under `/v1/`: requires authentication
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    @Value("\${app.config.jwt.secret:}")
    private val jwtSecret: String,
    @Value("\${app.config.jwt.issuer:blog-be}")
    private val jwtIssuer: String,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http
            .csrf { it.disable() } // Stateless API gateway — CSRF tokens are not meaningful.
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .authorizeExchange { ex ->
                ex
                    .pathMatchers(HttpMethod.GET, "/v1/posts/**", "/v1/tags/**", "/v1/search/**")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/refresh")
                    .permitAll()
                    .pathMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .pathMatchers("/fallback/**")
                    .permitAll()
                    .pathMatchers("/actuator/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated()
            }.oauth2ResourceServer { oauth ->
                oauth.jwt { it.jwtDecoder(jwtDecoder()).jwtAuthenticationConverter(jwtAuthConverter()) }
            }
        return http.build()
    }

    /**
     * HS256 + shared secret. Validators: standard (timestamp + iss) plus access-token `typ` check.
     */
    @Bean
    fun jwtDecoder(): NimbusReactiveJwtDecoder {
        require(jwtSecret.isNotBlank()) {
            "app.config.jwt.secret must be configured (env: APP_CONFIG_JWT_SECRET)"
        }
        val secretBytes = jwtSecret.toByteArray(Charsets.UTF_8)
        // HS256 minimum-security recommendation: 256-bit (32 bytes). RFC 7518 §3.2.
        require(secretBytes.size >= MIN_HS256_SECRET_BYTES) {
            "app.config.jwt.secret must be at least $MIN_HS256_SECRET_BYTES bytes (got ${secretBytes.size}). " +
                "Use 32+ bytes to meet the HS256 security recommendation."
        }
        val secretKey = SecretKeySpec(secretBytes, MacAlgorithm.HS256.name)
        val decoder =
            NimbusReactiveJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()

        val validators: OAuth2TokenValidator<Jwt> =
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(jwtIssuer),
                AccessTokenTypeValidator(),
            )
        decoder.setJwtValidator(validators)
        logger.info { "JwtDecoder configured (HS256, issuer=$jwtIssuer)" }
        return decoder
    }

    /**
     * Pin the authenticated principal name to the JWT `sub` claim. ReactiveJwtAuthenticationConverter
     * defaults to `sub` already, but we set it explicitly to stay stable if the claim mapping ever changes.
     */
    @Bean
    fun jwtAuthConverter(): ReactiveJwtAuthenticationConverter {
        val converter = ReactiveJwtAuthenticationConverter()
        converter.setPrincipalClaimName("sub")
        return converter
    }

    companion object {
        const val MIN_HS256_SECRET_BYTES = 32
    }
}

/**
 * Verify that the `typ` claim is "access". blog_be issues both access and refresh tokens; the gateway
 * must only accept access tokens.
 */
class AccessTokenTypeValidator : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val typ = token.claims["typ"]
        if (typ != ACCESS_TOKEN_TYPE) {
            return OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "typ claim must be '$ACCESS_TOKEN_TYPE', got: $typ", null),
            )
        }
        return OAuth2TokenValidatorResult.success()
    }

    companion object {
        // Must match the access-token identifier blog_be (PyJWT) stamps in `_issue()`.
        const val ACCESS_TOKEN_TYPE = "access"
    }
}
