<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# filters

## Purpose
Container for the request-pipeline filters. All current filters are cross-cutting and apply to
every route, so they live in `global/`. Per-route `GatewayFilterFactory` implementations, if ever
added, belong in a sibling package rather than in `global/`.

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `global/` | `WebFilter` + `GlobalFilter` implementations applied to all traffic (see `global/AGENTS.md`) |

<!-- MANUAL: -->
