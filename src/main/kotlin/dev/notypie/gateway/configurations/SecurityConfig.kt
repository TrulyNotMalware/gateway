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
 * Spring Security Resource Server + JWT 검증.
 *
 * blog_be (FastAPI) 가 발급한 HS256 토큰을 동일한 secret 으로 검증.
 * 토큰 payload 가정:
 *   - sub : 사용자 식별자 (ADMIN_USERNAME)
 *   - iss : "blog-be" (또는 ConfigMap 의 JWT_ISSUER)
 *   - exp : 만료
 *   - typ : "access" (refresh 토큰은 거부)
 *
 * Gateway 가 검증한 sub 는 [dev.notypie.gateway.filters.global.JwtUserIdInjectionFilter] 가
 * 다운스트림으로 X-User-ID 헤더로 박는다. (TrustHeaderStripFilter 가 외부 입력은 이미 제거)
 *
 * 인가 정책:
 *   - public: GET `/v1/posts/`, `/v1/tags/`, `/v1/search/`, POST `/v1/auth/login`, `/v1/auth/refresh`,
 *             `/fallback/`, `/actuator/` (mgmt 포트는 별개지만 안전상 명시)
 *   - 그 외 `/v1/`: authenticated 필요
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
            .csrf { it.disable() } // Stateless API gateway — CSRF 토큰 무의미
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
     * HS256 + 공유 secret. validators: 표준(timestamp + iss) + access 토큰 typ 검증.
     */
    @Bean
    fun jwtDecoder(): NimbusReactiveJwtDecoder {
        require(jwtSecret.isNotBlank()) {
            "app.config.jwt.secret must be configured (env: APP_CONFIG_JWT_SECRET)"
        }
        val secretBytes = jwtSecret.toByteArray(Charsets.UTF_8)
        // HS256 안전성 권고치: 최소 256-bit(32 bytes). RFC 7518 §3.2.
        require(secretBytes.size >= MIN_HS256_SECRET_BYTES) {
            "app.config.jwt.secret must be at least $MIN_HS256_SECRET_BYTES bytes (got ${secretBytes.size}). " +
                "HS256 권장 안전성을 위해 32 bytes 이상 사용."
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
     * 인증된 principal name 을 JWT 의 `sub` 클레임으로 통일. ReactiveJwtAuthenticationConverter 기본도
     * sub 를 쓰지만, 명시적으로 지정해 향후 클레임 매핑이 바뀌더라도 깨지지 않게 한다.
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
 * `typ` 클레임이 "access" 인지 검증. blog_be 는 access/refresh 토큰을 발급하는데, gateway 는 access 만 받아야 한다.
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
        // blog_be (PyJWT) 가 _issue() 에서 박는 access 토큰 식별자와 동일해야 함.
        const val ACCESS_TOKEN_TYPE = "access"
    }
}
