<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# configurations

## Purpose
All Spring bean definitions and the single `app.config.*` properties tree. This is where the
gateway's security posture is declared: which JWT issuers are trusted, which paths are public,
how Redis is wired, and what happens when Redis is unreachable.

## Key Files

| File | Description |
|------|-------------|
| `AppConfig.kt` | `@ConfigurationProperties("app.config")` root — `blacklist` / `redis` / `security` / `jwt` subtrees, plus the `RedisMode`, `StorageMode`, `RedisFailureMode`, `FailedNodeDetectorType` enums and `JacksonConfiguration` |
| `AppConfigValidator.kt` | `@PostConstruct` fail-fast startup checks (see below) |
| `SecurityConfig.kt` | `@EnableWebFluxSecurity` chain, per-issuer JWT decoders, and four custom `OAuth2TokenValidator`s |
| `RedisConfiguration.kt` | `RedisConfiguration` (standalone), `RedisClusterConfiguration`, `DisableRedis`, `NoRedisConfiguration` — mutually exclusive by `@Conditional` |
| `Conditions.kt` | `Condition` implementations (`OnDisableRedis`, `OnRedisRequired`, `OnRedisCluster`, `OnRedisStandalone`, `OnInMemoryRedisModule`) + the `Environment.extractAppConfig()` helper |
| `RemoteAddressResolverConfig.kt` | `XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)` — the only sanctioned way to read a client IP |

## For AI Agents

### Working In This Directory

**The `@Conditional` set must stay mutually exclusive.** `NoRedisConfiguration` carries an explicit
`OnInMemoryRedisModule` condition rather than `@ConditionalOnMissingBean` because Spring Boot 4
defaults `allow-bean-definition-overriding=false` and bean ordering within one evaluation round is
not deterministic. Two configurations registering `redisModule` is a startup crash, not a warning.

**`SecurityConfig` builds its validator chain by hand** instead of using
`JwtValidators.createDefaultWithIssuer(...)`. Reasons, all deliberate:
- the default installs a `JwtTypeValidator` that rejects RFC 9068's `typ=at+jwt`
- the default has **no audience check at all**
- the default accepts a token with no `exp` and no `sub`
- `X509CertificateThumbprintValidator` is omitted — inert without an mTLS token pipeline

The custom validators are `KidPresenceValidator` (rotation discipline), `RequiredClaimsValidator`
(`exp` + `sub` presence), `AccessTokenTypeValidator` (rejects `typ != "access"` when present), and
`audienceValidator(expected)`.

**Issuer resolution uses the `JwtIssuerReactiveAuthenticationManagerResolver` constructor**, not
`fromTrustedIssuers(...)` — the static factories require OIDC discovery at
`{issuer}/.well-known/openid-configuration`, while this gateway hits JWKS URIs directly over
cluster DNS. Unknown issuer → `Mono.empty()` → 401 with no fallback.

**Adding a backend requires no code change**: append an `app.config.jwt.issuers[N]` entry with
`issuer`, `jwks-uri`, and `audience`.

### Startup invariants enforced by `AppConfigValidator`
| Condition | Failure |
|-----------|---------|
| `blacklist.storage-mode=REDIS` + `redis.mode=NONE` | always fatal — would silently split the blacklist per pod |
| empty `gateway-shared-secret` | fatal in `prod` — disables `GatewayHopHeaderFilter`, letting any pod that reaches a backend forge the hop |
| `gateway-shared-secret` shorter than 32 chars (after trim) | fatal in `prod` |
| empty `jwt.issuers` | fatal in `prod` |
| an issuer entry with blank `issuer` / `jwks-uri` / `audience` | fatal in `prod` |
| duplicate issuer names | fatal in `prod` (resolver routes by `iss`, so last-write-wins would mask a misconfiguration) |

`MIN_GATEWAY_SHARED_SECRET_LEN = 32` mirrors `blog_be`'s own check — change both or neither.

### Blacklist admin toggle (`blacklist.admin-enabled`)
Registers `../endpoints/BlacklistEndpoint` on the management port. Default **false**, and
deliberately so: port 8081 has no authentication, and its only protection is the NetworkPolicy
admitting it from the `monitoring` namespace. `blacklist.admin-list-limit` caps the SCAN-backed
listing.

### Per-API-key rate limiting (`api-key-max-requests` + `allowed-api-keys`)
The limit applies only to keys listed in `allowed-api-keys`; an unrecognised `X-API-Key` is
ignored. That allowlist is the whole point — a counter keyed on an unvalidated client header is
evaded by sending a random key per request. Empty list (default) disables the dimension. The key
values belong in the Secret, not the ConfigMap.

### Redis failure stance (`app.config.security.redis-failure-mode`)
| Mode | Rate limit on Redis failure | Blacklist on Redis failure |
|------|-----------------------------|----------------------------|
| `FAIL_OPEN` | increment returns 0 → no throttle | allow |
| `FAIL_CLOSED` | returns `Long.MAX_VALUE` → deny all | deny |
| `HYBRID_IN_MEMORY` *(default)* | per-pod `ConcurrentHashMap` counter | allow (no per-pod blacklist exists — the state is only meaningful cluster-wide) |

### Two Jackson mappers coexist on purpose
`JacksonConfiguration` registers a `@Primary` `tools.jackson` (Jackson 3.x) `JsonMapper` for
application code, plus a plain `com.fasterxml.jackson.databind.ObjectMapper` because Redisson's
`JsonJacksonCodec` requires the classic 2.x type directly. Do not collapse them.

### Testing Requirements
`AppConfigValidatorSpec` (251 lines) and `SecurityValidatorsSpec` (217 lines) cover this package.
Any new startup invariant needs a matching negative case.

## Dependencies

### Internal
- `../modules/redis/` — the beans this package builds (`RedisModule`, `InMemoryRateLimitFallback`)
- `../filters/global/GatewayHopHeaderFilter` — consumes `security.gatewaySharedSecret`

### External
- `spring-boot-starter-oauth2-resource-server` / `NimbusReactiveJwtDecoder`
- `org.redisson:redisson-spring-boot-starter`
- `reactor.netty.http.client.HttpClient` — bounded JWKS fetch (3s connect / 5s response); default
  WebClient has no timeout, so one hung JWKS endpoint would stall that issuer indefinitely

<!-- MANUAL: -->
