<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# controllers

## Purpose
The gateway proxies rather than serves, so this package holds only the local endpoints that
Resilience4j circuit breakers forward to when a downstream is unavailable.

## Key Files

| File | Description |
|------|-------------|
| `FallbackController.kt` | `/fallback/be` (blog-be) and `/fallback/generic` (dok, fileserver) → HTTP 503 |

## For AI Agents

### Working In This Directory
- Route definitions reference these paths as `fallbackUri: forward:/fallback/be` and
  `forward:/fallback/generic` in `application-prod.yaml`. Renaming a mapping here silently breaks
  the breaker's fallback — grep the YAML first.
- `/fallback/**` is `permitAll()` in `SecurityConfig`; a forwarded request has no authenticated
  principal.
- The response body shape is `error` / `message` / `code` / `target` / `requestId` / `timestamp`
  with `code = "CIRCUIT_OPEN"`. `SecurityFilter` block responses use the same shape minus `target`.
  Keep them aligned — clients parse `code`.
- `requestId` is read back from the `X-Request-ID` header that `RequestIdFilter` already validated
  and stamped; do not mint a new one here.

### Testing Requirements
`FallbackControllerSpec` asserts status and body keys.

## Dependencies

### Internal
- `../filters/global/RequestIdFilter` — supplies the echoed `X-Request-ID`

### External
- `spring-boot-starter-webflux` (`@RestController`, `ServerWebExchange`)
- Triggered by `spring-cloud-starter-circuitbreaker-reactor-resilience4j`

<!-- MANUAL: -->
