<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# filters/global

## Purpose
The gateway's request pipeline. These seven filters implement the trust model: sanitize what the
client sent, stamp what the gateway verified, decide whether the request is allowed, and log it.
The order values are the contract — see the pipeline table in `../../AGENTS.md`.

## Key Files

| File | Type / Order | Description |
|------|--------------|-------------|
| `SecurityHeadersFilter.kt` | `WebFilter` / `HIGHEST_PRECEDENCE` | HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`, `X-XSS-Protection: 0`. Set via `beforeCommit` + set-if-absent, so a downstream value wins |
| `RequestIdFilter.kt` | `WebFilter` / `HIGHEST_PRECEDENCE + 10` | Reuses a client `X-Request-ID` only if it validates (1..128 chars, `[A-Za-z0-9_-]`), else mints a UUID. Echoes it on the response |
| `TrustHeaderStripFilter.kt` | `GlobalFilter` / `-200` | Removes `security.strippedTrustHeaders` from the inbound request. Pure input sanitization |
| `JwtUserIdInjectionFilter.kt` | `GlobalFilter` / `-150` | Reads `JwtAuthenticationToken` from `ReactiveSecurityContextHolder` and stamps `X-User-ID` = `sub` |
| `GatewayHopHeaderFilter.kt` | `GlobalFilter` / `-140` | Stamps `X-Gateway-Auth` = shared secret. No-ops when the secret is empty |
| `SecurityFilter.kt` | `GlobalFilter` / `-100` | Blacklist + rate-limit decision, response headers, block responses |
| `LoggingFilter.kt` | `GlobalFilter` / `-80` | One access-log line per request with resolved client IP and elapsed ms |

## For AI Agents

### Working In This Directory

**`RequestIdFilter` and `SecurityHeadersFilter` are `WebFilter`s deliberately** — that makes them
cover actuator and error responses, not just gateway-routed traffic.

**`JwtUserIdInjectionFilter` must stay a `GlobalFilter`.** A `WebFilter` runs before the gateway
chain, so a header stamped there would be deleted by `TrustHeaderStripFilter` (`-200`) before it
ever reached a backend.

**Never read the client IP from `X-Forwarded-For` directly.** Inject the `RemoteAddressResolver`
bean (`XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)`), which trusts N entries
*from the right* so a client cannot spoof a leftmost-prepended address. `SecurityFilter` and
`LoggingFilter` both do this.

**A null client IP is refused, not bucketed.** `SecurityFilter.getClientIp` returning null triggers
`ACCESS_DENIED`. Substituting a literal `"unknown"` key would put every such request in one shared
rate-limit bucket that a single client could exhaust for everyone.

### SecurityFilter specifics
- The blacklist check, the multi-dimension rate-limit check, and the login-specific rate-limit
  check run concurrently inside `coroutineScope` under a single `withTimeout(security.timeoutMs)`.
- `tighter(a, b)` combines two `RateLimitResult`s: **check `allowed` first**, then compare
  `remaining`. A denied result must win even when both have `remaining == 0`.
- Login paths (`security.loginPaths`) get a dedicated tight IP-keyed limit (default 10/min) because
  they are pre-auth — the generic 100/min endpoint quota is far too loose for a small fixed
  account set.
- `handleSecurityCheckFailure` exists because a Redis stall that exceeds `timeoutMs` cancels the
  coroutine *before* `ReactiveRedissonClientModule.increment`'s own fallback can run. It re-applies
  the operator's `redisFailureMode`, so `FAIL_CLOSED` does not silently degrade to `FAIL_OPEN` when
  Redis is merely slow. `HYBRID_IN_MEMORY` allows on timeout — the local counter was never reached.
- Block decisions go to the `"AUDIT"` named logger as
  `decision=BLOCK reason=… ip=… userId=… path=… requestId=…`. Keep that shape; it is parsed downstream.
- `X-API-Key` is still stripped at `-200`, but `TrustHeaderStripFilter` now captures the inbound
  value into the `CAPTURED_API_KEY_ATTR` exchange attribute first, and `SecurityFilter` reads it
  from there. The attribute is gateway-internal, so a client cannot set it; the header itself
  never reaches a backend. `SecurityFilter` honours the key **only** when it appears in
  `security.allowedApiKeys` — an unrecognised value is dropped rather than given a counter,
  because a client rotating random keys would otherwise mint a fresh bucket per request.
  With the list empty (the default) the dimension is inert.

### Testing Requirements
`SecurityFilterSpec` (528 lines) plus specs for `RequestIdFilter`, `TrustHeaderStripFilter`,
`SecurityHeadersFilter`, and `JwtUserIdInjectionFilter`. There is deliberately no
`LoggingFilterSpec` — it has no branching behaviour. When adding a filter, add its spec and assert
the order value explicitly.

## Dependencies

### Internal
- `../../service/` — `BlacklistService`, `RateLimitService`, `RateLimitConfig`, `RateLimitResult`
- `../../configurations/AppConfig` — every threshold and toggle
- `../../configurations/RemoteAddressResolverConfig` — the `RemoteAddressResolver` bean

### External
- `spring-cloud-gateway` `GlobalFilter` / `GatewayFilterChain` / `RemoteAddressResolver`
- `spring-security` `ReactiveSecurityContextHolder`, `JwtAuthenticationToken`
- `tools.jackson` `JsonMapper` — serializes block-response bodies

<!-- MANUAL: -->
