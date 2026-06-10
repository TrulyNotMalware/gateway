# API Gateway Service

## Features

- **Blacklist Filtering**: Block requests from specific IPs, user IDs, or API keys
- **Redis Integration**: Support for both Redis cluster and standalone configurations
- **Reactive Programming**: Built with Spring WebFlux and Kotlin Coroutines
- **Kubernetes Ready**: Includes deployment configurations for Kubernetes

## Requirements

- JDK 25
- Kotlin 2.3.0
- Redis (optional, can run in-memory mode)

## Configuration

The gateway supports different Redis configurations:

### Redis Standalone

```yaml
app:
  config:
    redis:
      mode: standalone
      password: your-password-here
      host: 127.0.0.1
      port: 6379
```

### Redis Cluster
```yaml
app:
  config:
    redis:
      mode: cluster
      cluster:
        nodes:
          - redis://redis-node-0:6379
          - redis://redis-node-1:6379
          - redis://redis-node-2:6379
      password: your-password-here
```

### In-Memory Mode (No Redis)
```yaml
app:
  config:
    redis:
      mode: none
```

## Building and Running

### Build with Gradle

```bash
./gradlew build
```

### Run Locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Docker

```bash
# Build Docker image
docker build -t gateway:latest .

# Run Docker container
docker run -p 8080:8080 gateway:latest
```

### Kubernetes

Deployment files are available in `src/main/resources/k8s/`.

```bash
kubectl apply -f src/main/resources/k8s/deployment.yaml
kubectl apply -f src/main/resources/k8s/service.yaml
```

## Security Policy

Defaults live in `AppConfig.Security` (Kotlin) and can be overridden via env
(`APP_CONFIG_SECURITY_*`) — typically through `k8s/dok/configmap.yaml`.

### RateLimit thresholds

| Key | Default | Notes |
|---|---|---|
| `ipMaxRequests` | 1000 / 60s | per source IP |
| `userMaxRequests` | 500 / 60s | per authenticated user |
| `apiKeyMaxRequests` | 1000 / 60s | per API key |
| `endpointMaxRequests` | 100 / 60s | per (endpoint, identifier) |
| `windowSeconds` | 60 | sliding window for all counters |

`RateLimitService.checkMultipleRateLimits` runs IP/user/api-key/endpoint checks
in parallel and applies the **tightest** remaining quota. The endpoint key's
identifier falls back `userId → apiKey → ip → "anonymous"`, so anonymous
traffic is still partitioned by IP (one bot cannot exhaust the quota for other
visitors).

Tune from real traffic by uncommenting the keys in
`k8s/dok/configmap.yaml`; no code change required.

### Redis failure policy (`redisFailureMode`)

When the Redis `INCRBY` call fails, `ReactiveRedissonClientModule.increment`
dispatches by the configured mode:

| Mode | Behaviour on Redis failure | When to pick it |
|---|---|---|
| `FAIL_OPEN` (default) | `increment` returns `0L`; RateLimit treats request as allowed | Read-heavy public traffic, RateLimit is an *additional* protection layer |
| `FAIL_CLOSED` | `increment` returns `Long.MAX_VALUE`; every request rejected (429) | Backend protection is more important than availability |
| `HYBRID_IN_MEMORY` | Falls back to a per-pod `InMemoryRateLimitFallback` (ConcurrentHashMap, same window semantics) | Want some throttle during Redis outages, accepting per-pod split state (effective limit × replica count) |

**Default rationale (`FAIL_OPEN`):** the gateway is a read-heavy blog edge and
RateLimit is an *additional* protection layer; the Blacklist module runs
independently, so known-bad IPs stay blocked even when Redis is down. Letting
Redis outages take down routing/CB/auth would cost more than a brief RateLimit
gap.

Switch stance with `APP_CONFIG_SECURITY_REDIS_FAILURE_MODE: "FAIL_CLOSED"` (or
`HYBRID_IN_MEMORY`) in the ConfigMap — no code change.

**Slow Redis (timeout) edge case**: when Redis stalls past
`security.timeoutMs`, the parallel security check is cancelled *before* the
per-call dispatch fires, so the in-memory fallback is never consulted. The
timeout handler honours the mode: `FAIL_OPEN` / `HYBRID_IN_MEMORY` allow the
request (HYBRID's intent is "throttle when Redis is unreachable", not "deny on
slow Redis"); `FAIL_CLOSED` blocks with `RATE_LIMITED`. Without this wiring
`FAIL_CLOSED` would silently degrade to fail-open on slow Redis.
