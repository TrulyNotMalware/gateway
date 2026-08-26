<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# gateway (application root package)

## Purpose
Root of the `dev.notypie.gateway` package. Holds the Spring Boot entrypoint and the four
functional areas the request passes through: `configurations` (beans + security policy),
`filters/global` (the request pipeline), `service` (blacklist / rate-limit logic), and
`modules/redis` (storage backends).

## Key Files

| File | Description |
|------|-------------|
| `GatewayApplication.kt` | `@SpringBootApplication` + `@ConfigurationPropertiesScan`. Explicitly **excludes** `RedissonAutoConfigurationV2` and `V4` |

### Why the Redisson auto-configs are excluded
`RedissonAutoConfigurationV2` references Spring Boot's removed `data.redis.RedisProperties` and
blows up on Spring Boot 4. `V4` would still spin up Redisson + DataRedis beans from default
`spring.data.redis` settings even in Redis-disabled mode. Redisson beans are therefore registered
by hand in `configurations/RedisConfiguration.kt`. Do not "simplify" this by removing the exclusion.

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `configurations/` | `AppConfig` properties tree, JWT/Security config, Redis wiring, startup validation (see `configurations/AGENTS.md`) |
| `filters/` | Request pipeline (see `filters/AGENTS.md`) |
| `service/` | `BlacklistService`, `RateLimitService` (see `service/AGENTS.md`) |
| `modules/` | Storage abstraction over Redis / in-memory (see `modules/AGENTS.md`) |
| `controllers/` | Circuit-breaker fallback endpoints (see `controllers/AGENTS.md`) |
| `endpoints/` | Actuator endpoints on the management port — blacklist admin (see `endpoints/AGENTS.md`) |
| `metrics/` | Micrometer instrumentation for the security path (see `metrics/AGENTS.md`) |

## For AI Agents

### The request pipeline — read this before touching any filter
Order is load-bearing. Two different Spring abstractions are interleaved:

| # | Component | Type | Order | Role |
|---|-----------|------|-------|------|
| 1 | `SecurityHeadersFilter` | `WebFilter` | `HIGHEST_PRECEDENCE` | Response hardening headers |
| 2 | `RequestIdFilter` | `WebFilter` | `HIGHEST_PRECEDENCE + 10` | Validate or mint `X-Request-ID` |
| 3 | Spring Security JWT chain | `WebFilter` | (framework) | Verify token → populate `ReactiveSecurityContextHolder` |
| 4 | `TrustHeaderStripFilter` | `GlobalFilter` | `-200` | **Remove** client-supplied `X-User-ID`, `X-API-Key`, `X-Internal-Auth`, `X-Gateway-Auth` (captures the API key into an exchange attribute first) |
| 5 | `JwtUserIdInjectionFilter` | `GlobalFilter` | `-150` | Re-stamp the *verified* `sub` as `X-User-ID` |
| 6 | `GatewayHopHeaderFilter` | `GlobalFilter` | `-140` | Stamp `X-Gateway-Auth` hop proof |
| 7 | `SecurityFilter` | `GlobalFilter` | `-100` | Blacklist + rate-limit decision |
| 8 | `LoggingFilter` | `GlobalFilter` | `-80` | Access log with resolved client IP |

`WebFilter` runs **before** the whole gateway `GlobalFilter` chain. That is why identity injection
must be a `GlobalFilter`: a header stamped in a `WebFilter` would be stripped again at step 4.
Anything that injects a header downstream services trust must sit **after** `-200`.

### Working In This Directory
- Adding a filter means picking an order value that states its relationship to the table above,
  and documenting it in the KDoc the way the existing filters do.
- Downstream services trust `X-User-ID` and `X-Gateway-Auth` absolutely. Any change that could let
  an externally supplied value reach a backend is a privilege-escalation bug, not a style issue.

### Testing Requirements
Every class here has a mirrored `*Spec.kt` under `src/test`. `SecurityFilterSpec` (528 lines) is
the widest net — run it after any pipeline change.

## Dependencies

### External
- `spring-cloud-starter-gateway-server-webflux` — `GlobalFilter`, `GatewayFilterChain`
- `kotlinx-coroutines-reactor` — `mono { }` / `awaitSingleOrNull()` bridging
- `io.github.oshai:kotlin-logging-jvm` — `KotlinLogging.logger {}`; the `"AUDIT"` named logger is
  used for block/allow decisions so logback can route them separately

<!-- MANUAL: -->
