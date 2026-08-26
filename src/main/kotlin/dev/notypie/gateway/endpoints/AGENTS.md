<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-26 | Updated: 2026-08-26 -->

# endpoints

## Purpose
Actuator endpoints served on the **management port (8081)**, not the public port. Anything here
is operator-facing tooling, never part of the request path.

## Key Files

| File | Description |
|------|-------------|
| `BlacklistEndpoint.kt` | `@Endpoint(id = "blacklist")` — list / check / add / remove blacklist entries |

## For AI Agents

### Why an actuator endpoint and not a `@RestController`
A controller is published on the public port (8080). Actuator endpoints bind to
`management.server.port`, and `k8s/networkpolicy.yaml` only admits traffic to 8081 from the
`monitoring` namespace. That port separation is the entire access control story — put nothing
here that would be unsafe for a `monitoring`-namespace workload to call.

### Three gates, all required
1. `app.config.blacklist.admin-enabled=true` — `@ConditionalOnProperty`, default **false**, so the
   bean does not even register otherwise
2. `blacklist` present in `management.endpoints.web.exposure.include`
3. the NetworkPolicy actually being enforced (needs a CNI that supports it — plain flannel is a
   no-op, and then 8081 is open to the cluster)

Keep the default off. The management port has no authentication.

### Working In This Directory
- Operations return `Mono` built with `mono { }` — actuator supports reactive return types, and
  `BlacklistService` is `suspend`.
- Use `@Endpoint` + `@ReadOperation`/`@WriteOperation`/`@DeleteOperation`. `@RestControllerEndpoint`
  still exists in Spring Boot 4 but is deprecated; do not reach for it.
- `@WriteOperation` parameters come from the JSON request body; `@DeleteOperation` and path-scoped
  reads use `@Selector`. A `@DeleteOperation` cannot take a body.
- An unknown blacklist type returns a body listing the supported values rather than throwing —
  an operator typo should not read as a server fault.
- Every mutation writes a `decision=BLACKLIST_ADD` / `BLACKLIST_REMOVE` line to the `AUDIT`
  logger. Preserve that: it is the only record of who changed the blocklist.

### Testing Requirements
`BlacklistEndpointSpec` calls the operations directly against a real `BlacklistService` over
`InMemoryModule` — no Spring context, no HTTP. New operations need a case covering the happy
path and the bad-input path.

## Dependencies

### Internal
- `../service/BlacklistService` — all reads and writes
- `../configurations/AppConfig` — `blacklist.adminEnabled`, `blacklist.adminListLimit`

### External
- `spring-boot-actuator` endpoint annotations
- `kotlinx-coroutines-reactor` — `mono { }`

<!-- MANUAL: -->
