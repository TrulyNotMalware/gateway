<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# test

## Purpose
Kotest specs mirroring the main package tree one-for-one under
`test/kotlin/dev/notypie/gateway/`. 78 tests across 12 spec classes; the suite is pure unit
testing — there is no Spring context, no Testcontainers, and no live Redis.

## Key Files

| File | Covers |
|------|--------|
| `configurations/AppConfigValidatorSpec.kt` | Every fail-fast startup invariant, positive and negative |
| `configurations/SecurityValidatorsSpec.kt` | `KidPresenceValidator`, `RequiredClaimsValidator`, `AccessTokenTypeValidator`, `audienceValidator` |
| `filters/global/SecurityFilterSpec.kt` | Largest spec — blacklist/rate-limit decisions, `tighter()`, timeout and failure-mode dispatch, block responses |
| `filters/global/RequestIdFilterSpec.kt` | Header validation, UUID minting, response echo |
| `filters/global/TrustHeaderStripFilterSpec.kt` | Strip behaviour and the no-op fast path |
| `filters/global/SecurityHeadersFilterSpec.kt` | Header presence and set-if-absent |
| `filters/global/JwtUserIdInjectionFilterSpec.kt` | `sub` → `X-User-ID`, unauthenticated pass-through |
| `modules/redis/InMemoryModuleSpec.kt` | Increment, TTL, expiry, delete |
| `modules/redis/InMemoryRateLimitFallbackSpec.kt` | Window carry-over and purge |
| `service/RateLimitServiceSpec.kt` | Limit thresholds, `checkMultipleRateLimits` selection |
| `service/BlacklistServiceSpec.kt` | Per-type keys, `isAnyBlacklisted` |
| `controllers/FallbackControllerSpec.kt` | 503 status and body shape |

## For AI Agents

### Conventions
- Class name is `<ClassUnderTest>Spec`, style is Kotest `BehaviorSpec` with
  `given` / `` `when` `` / `then`. `when` is a Kotlin keyword — it must be backtick-quoted.
- **Use the real `InMemoryModule` instead of mocking `RedisModule`.** Every service spec constructs
  a fresh instance per case so genuine increment and TTL semantics are exercised. MockK is
  available and used for Spring types (`ServerWebExchange`, `GatewayFilterChain`), not for storage.
- Construct a fresh module per `when` block; these classes hold mutable maps and leak state
  between cases otherwise.

### Working In This Directory
- Every production class currently has a matching spec except `LoggingFilter` (no branching) and
  `ReactiveRedissonClientModule` (needs a live Redis). Preserve that — a new class without a spec
  is an incomplete change.
- No `@SpringBootTest` anywhere. Adding one pulls in a full reactive context plus Redis wiring and
  changes the suite's cost profile; prefer constructing the class under test directly.
- Filter specs should assert `getOrder()` explicitly. The pipeline's correctness is entirely
  positional, and an order regression is otherwise invisible.

### Testing Requirements
```bash
./gradlew test
./gradlew test --tests '*SecurityFilterSpec*'
```
Test JVM args come from `build.gradle.kts`: `-Xmx4g` plus `--add-opens java.base/java.lang` and
`java.base/java.util` (required by MockK). Failures upload `build-reports.zip` in CI.

## Dependencies

### External
- `io.kotest:kotest-runner-junit5`, `kotest-assertions-core`, `kotest-extensions-spring` (BOM 6.2.0)
- `io.mockk:mockk` 1.14.11
- `io.projectreactor:reactor-test`, `kotlinx-coroutines-test`
- `spring-boot-starter-test` with `junit-vintage-engine` excluded

<!-- MANUAL: -->
