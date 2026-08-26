<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-26 | Updated: 2026-08-26 -->

# metrics

## Purpose
Micrometer instrumentation for the security path. Scraped by Prometheus at
`:8081/actuator/prometheus` (see the scrape annotations in `k8s/deployment.yaml`).

## Key Files

| File | Description |
|------|-------------|
| `SecurityMetrics.kt` | `gateway.security.blocks`, `gateway.security.check.failures`, `gateway.security.check.duration` |

## For AI Agents

### Why these three exist
The block decision and the Redis-failure fallback were previously visible **only in logs**, which
cannot be alerted on. The `HYBRID_IN_MEMORY` default makes this worse: it absorbs a Redis outage
silently — the request still succeeds — so absent a counter there is no signal at all that the
cluster-wide counters have stopped working and every pod is now throttling on its own.

| Meter | Tags | Answers |
|-------|------|---------|
| `gateway.security.blocks` | `reason` | how much traffic is being refused, and why |
| `gateway.security.check.failures` | `kind`, `failureMode`, `outcome` | is Redis degrading, and what is the configured stance doing about it |
| `gateway.security.check.duration` | `outcome` | is `security.timeout-ms` anywhere near the real latency distribution |

A fourth, `gateway.redis.operation.failures` (`operation`, `failureMode`), is emitted from
`../modules/redis/ReactiveRedissonClientModule` — that class is constructed by hand in
`RedisConfiguration`, so its registry arrives as a nullable constructor argument rather than by
injection.

### Working In This Directory
- `reason` on the block counter is the same string as the audit log's `reason=` field. Keep them
  identical so a dashboard and a log search agree.
- Tag values must stay low-cardinality. Never tag with an IP, user id, API key, or raw path —
  that is what the audit log is for.
- `SecurityFilter` stops the duration timer **before** forwarding downstream, so the metric
  measures the check alone. Do not move the `stopCheckTimer` call past `chain.filter`.

### Testing Requirements
Assert against a `SimpleMeterRegistry` — see the metrics cases at the end of
`SecurityFilterSpec`. A new meter needs a test that its name and tags are what a dashboard would
query, since a typo there is invisible at runtime.

## Dependencies

### Internal
- Consumed by `../filters/global/SecurityFilter`
- `../configurations/RedisFailureMode` — tag value on the failure counter

### External
- `io.micrometer:micrometer-core`; exported by `micrometer-registry-prometheus`

<!-- MANUAL: -->
