<!-- Parent: ../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# main/resources

## Purpose
Spring profile configuration and the Kubernetes manifests that deploy this service. There is no
`application.yaml` — every profile is self-contained and activated by `SPRING_PROFILES_ACTIVE`.

## Key Files

| File | Description |
|------|-------------|
| `application-local.yaml` | `local` profile: trusts all proxies (`.*`), blacklist **and** rate limit disabled, single `blog-be` issuer at `localhost:8000`, actuator wide open on the same port |
| `application-prod.yaml` | `prod` profile: route table, CORS, circuit breakers, compression, netty tuning, management on port 8081 |
| `banner.txt` | ASCII startup banner |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `k8s/` | Deployment manifests (see `k8s/AGENTS.md`) |

## For AI Agents

### The `trusted-proxies` invariant
`application-prod.yaml` trusts all RFC1918 ranges:
```
trusted-proxies: "127\\.0\\.0\\.1|10\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|192\\.168\\..*"
```
This is **only safe because `k8s/networkpolicy.yaml` restricts ingress on port 8080 to the
`istio-system` data plane**. Any pod matching the regex that can reach 8080 directly could spoof
`X-Forwarded-For` and evade per-IP rate limiting and blacklisting. Change the regex and the
NetworkPolicy together, or neither. Narrowing to the real pod CIDR is preferred.

### Route table (`prod`), evaluated in order
| id | Predicate | Notes |
|----|-----------|-------|
| `PRJ-DOK-FILTER` | `/dok/**` | `RewritePath` strips the prefix, `RequestSize=5MB`, breaker `dok-cb` |
| `FILE-UPLOAD` | `/v1/files/upload/**`, POST+PUT | `RequestSize=100MB`, per-route `response-timeout: 300000` |
| `FILE-DOWNLOAD` | `/v1/files/**` | `response-timeout: 120000` |
| `BLOG-CACHED` | `/v1/posts/**`, `/v1/tags/**`, `/v1/search/**`, GET | adds `LocalResponseCache` 5MB / 5s |
| `BLOG-DEFAULT` | `/v1/**` | catch-all |

`BLOG-DEFAULT` matches `/v1/**`, so a new backend route must be declared **above** it.

### Working In This Directory
- Timeouts are layered and easy to get wrong: `httpclient.response-timeout` (global) < per-route
  `metadata.response-timeout` < `resilience4j.timelimiter` (`file-be-cb` is 300s to match the
  upload route). Raising one without the others produces a breaker trip instead of a slow success.
- `prod` deliberately omits the `gateway` actuator endpoint — a full route dump is recon surface
  with no consumer. `health.show-details` is `never` in prod, `always` in local.
- `forward-headers-strategy: none` in both profiles: `XForwardedRemoteAddressResolver` handles XFF,
  and letting the framework also parse it would double-process the header.
- `${...}` placeholders (`PRJ_BE_URI`, `PRJ_DOK_ORIGINS`, `PROD_HTTP_CLIENT_*`) are supplied by
  `k8s/configmap.yaml`. Adding one here means adding it there or the context fails to start.
- `app.config.jwt.issuers` is `[]` in `application-prod.yaml` on purpose — the real list arrives as
  indexed env vars from the ConfigMap, and `AppConfigValidator` makes an empty list fatal in prod.

### Testing Requirements
These files are not covered by unit tests. Validate a change by booting with the `local` profile,
or by rendering the ConfigMap env against `AppConfig`'s relaxed-binding names.

## Dependencies

### Internal
- `../kotlin/dev/notypie/gateway/configurations/AppConfig.kt` — binds the whole `app.config` tree
- `../kotlin/dev/notypie/gateway/controllers/FallbackController.kt` — targets of `fallbackUri`

<!-- MANUAL: -->
