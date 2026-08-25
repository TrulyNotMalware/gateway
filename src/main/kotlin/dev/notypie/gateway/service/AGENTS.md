<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# service

## Purpose
The two decision services `SecurityFilter` consults. Both are thin, stateless `@Service` beans over
`RedisModule` — all storage semantics (atomicity, TTL, failure stance) live in `../modules/redis/`.

## Key Files

| File | Description |
|------|-------------|
| `BlacklistService.kt` | IP / user / API-key blacklist reads and writes; `BlacklistType` enum |
| `RateLimitService.kt` | Fixed-window counters per IP, user, endpoint, and login path; `RateLimitResult` and `RateLimitConfig` data classes |

## For AI Agents

### Key namespaces
Prefixes are `const` in each service's companion. Changing one orphans every live counter, so treat
them as a wire format:

| Prefix | Written by |
|--------|-----------|
| `blacklist:ip:` / `blacklist:user:` / `blacklist:api_key:` | `BlacklistService` |
| `rate_limit:ip:` / `rate_limit:user:` | `RateLimitService` |
| `rate_limit:endpoint:<endpoint>:<identifier>` | `checkEndpointRateLimit` — identifier is `userId ?: ip ?: "anonymous"` |
| `rate_limit:login:<ip>` | `checkLoginRateLimit` — its own prefix so it never collides with the generic IP counter |
| `rate_limit:api_key:<key>` | `checkApiKeyRateLimit` — only reached for keys in `security.allowedApiKeys` |

### Working In This Directory
- `checkMultipleRateLimits` fans out with `async` + `awaitAll` and returns the result with the
  **smallest `remaining`**. Note it selects by `remaining`, not by `allowed` — `SecurityFilter.tighter`
  is what guarantees a denial wins. Keep that division of responsibility in mind before changing either.
- `isAnyBlacklisted` checks IP and (when present) user concurrently and short-circuits on any hit.
  With no checks queued it returns `false`.
- `checkRateLimit` increments **then** reads TTL — two round trips. Every counter dimension costs
  two Redis calls per request; adding a dimension is not free.
- `addApiKeyToBlacklist` / `BlacklistType.API_KEY` still have no live caller. The *rate-limit*
  dimension is wired (`checkApiKeyRateLimit`, fed by `TrustHeaderStripFilter`'s captured attribute),
  but the blacklist side is not — `isAnyBlacklisted` takes only `ip` and `userId`. Wiring it is a
  natural follow-up; the validated key is already available in `SecurityFilter`.

### Testing Requirements
`BlacklistServiceSpec` and `RateLimitServiceSpec` construct the real service over a fresh
`InMemoryModule` per case — no mocking of `RedisModule`. Follow that pattern so window and TTL
behaviour is actually exercised.

## Dependencies

### Internal
- `../modules/redis/RedisModule` — the only storage dependency
- Consumed by `../filters/global/SecurityFilter`

### External
- `kotlinx-coroutines-core` — `async` / `awaitAll` / `coroutineScope`

<!-- MANUAL: -->
