<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# modules/redis

## Purpose
The key-value abstraction (`RedisModule`) that `BlacklistService` and `RateLimitService` are built
on, plus its two implementations and the degraded-mode fallback counter. Which implementation is
registered is decided entirely by `@Conditional` in `../../configurations/RedisConfiguration.kt`.

## Key Files

| File | Description |
|------|-------------|
| `RedisModule.kt` | The interface: `set`, `get`, `exists`, `delete`, `increment`, `remainingTtl` — all `suspend` |
| `ReactiveRedissonClientModule.kt` | Production implementation over `RedissonReactiveClient` |
| `InMemoryModule.kt` | Full `RedisModule` over `ConcurrentHashMap`; used when `redis.mode=NONE` or `blacklist.storage-mode=IN_MEMORY`. Also the standard test double |
| `InMemoryRateLimitFallback.kt` | *Not* a `RedisModule` — only `increment` + `remainingTtl`, used by `HYBRID_IN_MEMORY` when a live Redis call throws |

## For AI Agents

### Working In This Directory

**`increment` uses a Lua script, not INCRBY + EXPIRE.** The two commands must be atomic: `EXPIRE`
fires only when the returned value equals the increment (i.e. first write of a window), so a crash
between them cannot leave a key with no TTL. Do not replace it with separate calls.

```lua
local v = redis.call('INCRBY', KEYS[1], ARGV[1])
if v == tonumber(ARGV[1]) then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
return v
```

**`exists` deliberately rethrows while the others swallow.** It backs the blacklist check —
returning a fabricated `false` on Redis failure would silently disable the blacklist regardless of
`redisFailureMode`. Propagating lets `SecurityFilter.handleSecurityCheckFailure` apply the
operator's configured stance. `set`/`get`/`delete` degrade quietly by design; keep that asymmetry.

**Failure dispatch lives in `increment` and `remainingTtl`:**

| `redisFailureMode` | `increment` on failure | `remainingTtl` on failure |
|--------------------|------------------------|---------------------------|
| `FAIL_OPEN` | `0L` (no throttle) | `-2L` |
| `FAIL_CLOSED` | `Long.MAX_VALUE` (deny) | `-2L` |
| `HYBRID_IN_MEMORY` | delegate to `InMemoryRateLimitFallback` | delegate |

`-2L` means "key missing" — reporting no info is preferred over fabricating a TTL for a response
header.

**`InMemoryRateLimitFallback` mirrors only the counter surface, never the blacklist.** Blacklist
state is only meaningful cluster-wide; a per-pod copy would be worse than the current behaviour.
Its `increment` carries over the existing window's deadline so a sustained burst keeps incrementing
one counter — matching the Lua script's "EXPIRE only on first INCR" semantics.

**The `HYBRID_IN_MEMORY` + null-fallback combination is rejected in the constructor** via `require`,
so a misconfigured deployment fails in the startup log rather than on the first Redis hiccup.

**Both in-memory classes are `CoroutineScope` + `DisposableBean`** with a `@PostConstruct` cleanup
loop every 5 minutes and `job.cancel()` + `clear()` on destroy. A new map-backed class here needs
the same lifecycle or it leaks expired keys.

### Testing Requirements
`InMemoryModuleSpec` and `InMemoryRateLimitFallbackSpec` cover the in-memory paths. There is no
spec for `ReactiveRedissonClientModule` — it needs a live Redis; verify changes to it through
`RateLimitServiceSpec` against `InMemoryModule` plus manual testing.

## Dependencies

### Internal
- `../../configurations/RedisFailureMode` — the failure-stance enum
- Consumed by `../../service/BlacklistService` and `../../service/RateLimitService`

### External
- `org.redisson` — `RedissonReactiveClient`, `RScript`, `LongCodec`
- `kotlinx-coroutines-reactive` — `awaitSingle()` / `awaitFirstOrNull()`

<!-- MANUAL: -->
