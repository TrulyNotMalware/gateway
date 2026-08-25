<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# src

## Purpose
Standard Gradle source root. All Kotlin lives under the `dev.notypie.gateway` package; the test
tree mirrors the main tree package-for-package. Runtime configuration (Spring profiles) and the
Kubernetes manifests that deploy this service both live under `main/resources`.

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `main/kotlin/dev/notypie/gateway/` | Application code (see that directory's `AGENTS.md`) |
| `main/resources/` | Spring profile YAML, banner, k8s manifests (see `main/resources/AGENTS.md`) |
| `test/` | Kotest specs mirroring the main package tree (see `test/AGENTS.md`) |

Intermediate package directories (`main/kotlin`, `main/kotlin/dev`, `main/kotlin/dev/notypie`)
carry no files of their own and intentionally have no `AGENTS.md`.

## For AI Agents

### Working In This Directory
- There is exactly one Gradle module. Do not introduce `src/main/java` — the project is
  Kotlin-only and `compileJava` is `NO-SOURCE` by design.
- New packages go under `dev.notypie.gateway.<area>`; add the mirrored test package at the same
  time (every production class in this repo has a matching `*Spec.kt`).

### Testing Requirements
`./gradlew test` runs the whole tree via JUnit Platform with Kotest as the engine. Test JVM args
are set in `build.gradle.kts` (`-Xmx4g`, `--add-opens java.base/java.lang` and `java.util` for
MockK).

### Common Patterns
- Test classes are named `<ClassUnderTest>Spec` and use Kotest's `BehaviorSpec`
  (`given` / `when` / `then`).
- Unit tests wire `InMemoryModule` in place of Redis rather than mocking `RedisModule`, so the
  real increment/TTL semantics are exercised.

## Dependencies

### Internal
- `main/resources/application-*.yaml` supplies the `app.config.*` tree that
  `configurations/AppConfig.kt` binds — the two must be changed together.

<!-- MANUAL: -->
