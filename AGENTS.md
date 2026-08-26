<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# gateway

## Purpose
Spring Cloud Gateway (WebFlux, reactive) edge proxy for the `notypie.dev` platform. It is the
single public entrypoint: it verifies JWTs issued by *each backend* (multi-issuer, RS256 via
JWKS), strips client-supplied trust headers, stamps verified identity and a hop-proof secret,
applies blacklist + rate limiting backed by Redis, and routes to downstream services
(`blog-be`, `dok`, `fileserver`) behind Resilience4j circuit breakers.

The gateway never mints tokens — it is purely a verifier. Backends enforce the `X-Gateway-Auth`
hop proof, so traffic must traverse this service; routing the public hostname straight at a
backend breaks the trust model.

## Key Files

| File | Description |
|------|-------------|
| `build.gradle.kts` | Kotlin JVM 2.4.10 + Spring Boot 4.1.0 + ktlint 14.2.0; Java 25 toolchain (Adoptium); BOM-driven dependency versions |
| `settings.gradle.kts` | Single-module build, `rootProject.name = "gateway"` |
| `gradle.properties` | Gradle daemon JVM args (6g, ZGC), configuration cache + parallel + build cache all on |
| `Dockerfile` | `eclipse-temurin:25.0.1_8-jre-alpine`, copies `build/libs/gateway-alpha.jar`, exec-form ENTRYPOINT so SIGTERM reaches the JVM |
| `.dockerignore` | Keeps the build context to the bootJar only |
| `.editorconfig` | ktlint-backed formatting rules |
| `.gitmessage` | Commit template — `<타입> : <제목>` (`feat`/`fix`/`docs`/`test`/`refact`/`style`/`chore`) |
| `README.md` | Human-facing operational documentation |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `src/` | Kotlin sources, config, and k8s manifests (see `src/AGENTS.md`) |
| `.github/` | Dependabot config and CI workflows (see `.github/AGENTS.md`) |
| `gradle-config/` | OS-specific `gradle.properties` presets and installer (see `gradle-config/AGENTS.md`) |
| `gradle/` | Gradle wrapper (9.7.0) — regenerate only via `./gradlew wrapper` |

## For AI Agents

### Working In This Directory
- **Commit style is enforced by convention, not a hook**: `type : subject`, no trailing period,
  50 chars max, body bullets prefixed with `-`. Match the existing log.
- Never hand-edit `gradle/wrapper/*` or `gradlew*`; those come from Dependabot or `./gradlew wrapper`.
  `.gitattributes` declares `*.bat text eol=crlf`, so a `gradlew.bat` committed with CRLF blobs
  shows up permanently dirty — fix with `git add --renormalize`, not by editing the file.
- The Java toolchain is pinned to **25 / Adoptium**. Gradle will refuse to build against another
  vendor; install Temurin 25 rather than loosening `build.gradle.kts`.

### Testing Requirements
```bash
./gradlew build        # compile + ktlintCheck + test (the full gate)
./gradlew test         # tests only
./gradlew ktlintCheck  # lint only
./gradlew ktlintFormat # autofix
```
CI does **not** run tests on `main` — `simple_test_action.yaml` and `lint.yaml` only trigger on
`feature/*`, `feat/*`, `features/*` branches. Anything merged straight to `main` is unverified by
CI, so run `./gradlew build` locally before pushing there.

### Common Patterns
- Operator tooling goes on the management port (8081) as an actuator `@Endpoint`, never as a
  `@RestController` — a controller would be published on the public port.
- Kotlin coroutines bridged into Reactor via `mono { }` / `awaitSingleOrNull()` — the codebase
  prefers `suspend` functions internally and converts at the Spring boundary.
- Configuration is a single `@ConfigurationProperties("app.config")` tree (`AppConfig`), bound
  from env vars using Spring relaxed binding (`APP_CONFIG_SECURITY_TRUSTED_PROXY_HOPS`, indexed
  lists as `APP_CONFIG_JWT_ISSUERS_0_ISSUER`).
- Invalid config combinations fail fast at startup in `AppConfigValidator`, not at first request.

## Dependencies

### External
- Spring Boot 4.1.0 / Spring Cloud 2025.1.2 — `spring-cloud-starter-gateway-server-webflux`
- Spring Security + `oauth2-resource-server` — multi-issuer JWT verification
- Redisson 4.6.0 — reactive Redis client (standalone + cluster)
- Resilience4j (`spring-cloud-starter-circuitbreaker-reactor-resilience4j`) — per-route breakers
- Micrometer + Prometheus registry — metrics on the management port
- Jackson 3.x (`tools.jackson`) for app code, classic Jackson 2.x kept only for Redisson's codec
- Kotest 6.2.0 + MockK 1.14.11 — test stack

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
