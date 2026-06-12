package dev.notypie.gateway.configurations

import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtTypeValidator
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Spring Security Resource Server + multi-issuer JWT verification.
 *
 * The gateway is purely a verifier; each backend signs its own tokens with its own RSA
 * private key and exposes a `/.well-known/jwks.json`. Adding a new backend means adding
 * an entry to `app.config.jwt.issuers` — no code change, regardless of the backend's
 * language. Token routing is by the `iss` claim.
 *
 * Expected token payload:
 *   - sub : user identifier
 *   - iss : must match one of the configured issuers
 *   - exp : expiry
 *   - typ : (optional) "access" — if present and not "access", the token is rejected
 *           (lets us reject refresh tokens at the gateway without forcing every backend
 *           to adopt the `typ` convention)
 *
 * The verified `sub` is stamped into the X-User-ID header by
 * [dev.notypie.gateway.filters.global.JwtUserIdInjectionFilter] (TrustHeaderStripFilter has already
 * removed any external input).
 *
 * Authorization policy:
 *   - public: GET `/v1/posts/`, `/v1/tags/`, `/v1/search/`, `/v1/content/`,
 *             POST `/v1/auth/login`, `/v1/auth/refresh`,
 *             file-be auth flow: GET `/v1/files/auth/public-key` (needed pre-login to
 *             RSA-encrypt the password), POST `/v1/files/auth/login`, `/v1/files/auth/refresh`,
 *             `/v1/files/auth/logout` (cookie-only — no Bearer token after access expiry),
 *             `/fallback/`, `/actuator/` (mgmt port is separate but listed for safety)
 *   - everything else under `/v1/`: requires authentication
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val appConfig: AppConfig,
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
                    .pathMatchers(HttpMethod.GET, "/v1/posts/**", "/v1/tags/**", "/v1/search/**", "/v1/content/**")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/refresh")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/v1/files/auth/public-key")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.POST,
                        "/v1/files/auth/login",
                        "/v1/files/auth/refresh",
                        "/v1/files/auth/logout",
                    ).permitAll()
                    .pathMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .pathMatchers("/fallback/**")
                    .permitAll()
                    .pathMatchers("/actuator/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated()
            }.oauth2ResourceServer { oauth ->
                oauth.authenticationManagerResolver(jwtAuthenticationManagerResolver())
            }
        return http.build()
    }

    /**
     * Per-issuer authentication-manager resolver. The `iss` claim picks the manager;
     * unknown issuers return an empty Mono → Spring Security 401 with no fallback.
     *
     * Each per-issuer decoder is lazy: Nimbus only hits its JWKS URI on the first JWT that
     * targets that issuer. After that the JWK set is cached (Nimbus default: 5 min) and new
     * `kid` headers trigger a refresh.
     */
    @Bean
    fun jwtAuthenticationManagerResolver(): ReactiveAuthenticationManagerResolver<ServerWebExchange> {
        require(appConfig.jwt.issuers.isNotEmpty()) {
            "app.config.jwt.issuers must contain at least one issuer entry " +
                "(env: APP_CONFIG_JWT_ISSUERS_0_ISSUER + APP_CONFIG_JWT_ISSUERS_0_JWKS_URI)"
        }
        // Build managers explicitly (not via `associate`) so duplicate issuer names in any
        // profile — not only prod — surface as an error. The resolver routes by `iss`, so
        // "last write wins" would silently mask a misconfiguration.
        val managers: MutableMap<String, ReactiveAuthenticationManager> = LinkedHashMap()
        appConfig.jwt.issuers.forEach { entry ->
            require(entry.issuer.isNotBlank() && entry.jwksUri.isNotBlank()) {
                "app.config.jwt.issuers entries must have both 'issuer' and 'jwks-uri' set"
            }
            require(entry.issuer !in managers) {
                "Duplicate issuer '${entry.issuer}' in app.config.jwt.issuers — must be unique"
            }
            logger.info { "JWT verifier configured: issuer=${entry.issuer} jwksUri=${entry.jwksUri}" }
            managers[entry.issuer] = buildManager(entry)
        }
        // Use the constructor (not fromTrustedIssuers): the static factories internally
        // call ReactiveJwtDecoders.fromIssuerLocation(...), which requires OIDC discovery
        // at `{issuer}/.well-known/openid-configuration`. We hit JWKS URIs directly via
        // internal cluster DNS, so we wire our own Map lookup. Unknown issuer → Mono.empty
        // → OAuth2AuthenticationException → 401 with no fallback. A slow/failing JWKS for
        // one issuer never blocks tokens from other issuers — managers are independent.
        return JwtIssuerReactiveAuthenticationManagerResolver { issuer ->
            Mono.justOrEmpty(managers[issuer])
        }
    }

    private fun buildManager(entry: AppConfig.Jwt.IssuerEntry): ReactiveAuthenticationManager {
        val decoder =
            NimbusReactiveJwtDecoder
                .withJwkSetUri(entry.jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .webClient(jwksWebClient())
                .build()
        // Built explicitly instead of `JwtValidators.createDefaultWithIssuer(...)` because that
        // helper installs a `JwtTypeValidator.jwt()` accepting only header `typ=JWT` / absent.
        // RFC 9068-compliant backends emit `typ=at+jwt`, so we widen the allowlist.
        //
        // Note: the default chain also includes `X509CertificateThumbprintValidator`, which only
        // matters for cert-bound tokens (RFC 8705 mTLS, `cnf.x5t#S256`). The gateway has no mTLS
        // user-token pipeline today, so omitting it is intentional and inert. Add it back if a
        // backend ever issues cert-bound access tokens through this gateway.
        val typValidator =
            JwtTypeValidator(listOf("JWT", "at+jwt", "application/at+jwt"))
                .apply { setAllowEmpty(true) }
        val validators =
            mutableListOf<OAuth2TokenValidator<Jwt>>(
                JwtTimestampValidator(),
                JwtIssuerValidator(entry.issuer),
                typValidator,
                RequiredClaimsValidator(),
                KidPresenceValidator(),
                AccessTokenTypeValidator(),
            )
        // Audience is per-issuer, declared by the backend that mints the tokens. If the
        // contract is set, enforce it: missing `aud`, empty list, or no match → 401. This
        // catches a token issued for one verifier being replayed against another that
        // happens to trust the same issuer's signing key.
        if (entry.audience.isNotBlank()) {
            validators.add(audienceValidator(entry.audience))
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        val manager = JwtReactiveAuthenticationManager(decoder)
        manager.setJwtAuthenticationConverter(jwtAuthConverter())
        return manager
    }

    /**
     * Bounded-timeout WebClient for JWKS fetches. Default Spring WebClient has no timeout —
     * a hung or slow JWKS endpoint would tie up authentication for that issuer indefinitely.
     * Each per-issuer decoder builds its own client instance so connection-pool state stays
     * isolated; other issuers continue working when one JWKS endpoint is slow.
     */
    private fun jwksWebClient(): WebClient {
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, JWKS_CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofMillis(JWKS_RESPONSE_TIMEOUT_MS))
        return WebClient.builder().clientConnector(ReactorClientHttpConnector(httpClient)).build()
    }

    companion object {
        private const val JWKS_CONNECT_TIMEOUT_MS = 3000
        private const val JWKS_RESPONSE_TIMEOUT_MS = 5000L
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
}

/**
 * Reject tokens that do not carry a `kid` header.
 *
 * With a single-key JWKS, Nimbus's default behaviour is to match a kid-less token against
 * any compatible RSA key it can find. That works today but silently weakens rotation
 * discipline: once the JWKS holds [v1, v2], a kid-less token would be ambiguous and could
 * match the *wrong* key. Enforcing a non-blank kid at verification time keeps the issuer/
 * verifier contract explicit (blog_be stamps `JWT_KID` on every issuance — see
 * `app/core/security/auth.py`).
 */
class KidPresenceValidator : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val kid = token.headers["kid"]
        if (kid !is String || kid.isBlank()) {
            return OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "JWT header must include a non-blank 'kid'", null),
            )
        }
        return OAuth2TokenValidatorResult.success()
    }
}

/**
 * Require `exp` and `sub` claims to be present.
 *
 * Spring Security's default validator chain (`JwtValidators.createDefaultWithIssuer`) only
 * rejects an *expired* token — a token with no `exp` claim at all passes through unbounded.
 * Same for `sub`: the converter principal is `sub`, but no validator enforces its presence,
 * so a token without `sub` would produce a null principal downstream. We enforce both here.
 */
class RequiredClaimsValidator : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val errors = mutableListOf<OAuth2Error>()
        if (token.expiresAt == null) {
            errors.add(OAuth2Error("invalid_token", "Required claim 'exp' is missing", null))
        }
        if (token.subject.isNullOrBlank()) {
            errors.add(OAuth2Error("invalid_token", "Required claim 'sub' is missing", null))
        }
        return if (errors.isEmpty()) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(errors)
        }
    }
}

/**
 * Reject tokens that look like refresh tokens (or anything other than access) when issuers
 * use the `typ` claim convention.
 *
 * Backwards-compatible with backends that don't stamp `typ`: a missing claim passes through.
 * blog_be (and any backend that mints both access + refresh tokens) is expected to set
 * `typ="access"` on access tokens; that convention is what lets the gateway reject refresh
 * tokens at the edge without forcing every multi-language backend to adopt the same scheme.
 *
 * **Backend integration contract**: if a backend mints refresh tokens of any kind, it MUST
 * either (a) stamp `typ="refresh"` on them — this validator then rejects them, or
 * (b) use a separate `iss` so the gateway is never asked to verify them at all
 * (recommended — keep refresh-token verification entirely inside the issuing service).
 * Forwarding refresh tokens through the gateway without one of these is the only way the
 * relaxation introduces a real risk.
 */
class AccessTokenTypeValidator : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val typ = token.claims["typ"] ?: return OAuth2TokenValidatorResult.success()
        if (typ != ACCESS_TOKEN_TYPE) {
            return OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "typ claim must be '$ACCESS_TOKEN_TYPE' when present, got: $typ",
                    null,
                ),
            )
        }
        return OAuth2TokenValidatorResult.success()
    }

    companion object {
        // Convention shared with any backend that distinguishes access/refresh tokens.
        const val ACCESS_TOKEN_TYPE = "access"
    }
}

/**
 * Pins the token's `aud` claim to the issuer's declared audience.
 *
 * `Jwt.getAudience()` always materialises as `List<String>` regardless of whether the
 * original claim was a string or array (RFC 7519 allows both). The default validator
 * chain has NO audience check at all, so a token without `aud` would otherwise pass —
 * this explicitly rejects null/empty as well as mismatches.
 */
fun audienceValidator(expected: String): OAuth2TokenValidator<Jwt> =
    JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { aud ->
        !aud.isNullOrEmpty() && expected in aud
    }
