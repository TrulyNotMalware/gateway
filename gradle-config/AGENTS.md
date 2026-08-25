<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# gradle-config

## Purpose
OS-specific `gradle.properties` presets and a script that installs the right one into the project
root. Developer tooling only — nothing here is on the build or runtime path, and CI does not use it.

## Key Files

| File | Description |
|------|-------------|
| `apply.sh` | Detects the OS and copies the matching preset to `../gradle.properties`; accepts `force` or `common` |
| `gradle-macos.properties` | ZGC without Linux-specific flags, Apple Silicon tuning, 6GB heap, file-system watching |
| `gradle-linux.properties` | 8GB heap, transparent huge pages, heavier parallelism |
| `README.md` | Usage and the rationale for each preset |

## For AI Agents

### Working In This Directory
- `apply.sh` **overwrites the project's `gradle.properties`**, which is a tracked file. Running it
  produces a working-tree change; check `git diff` before committing so a machine-local preset does
  not land on `main`.
- The committed root `gradle.properties` is the macOS-derived preset (6GB, ZGC, configuration cache
  on). Treat it as the shared default rather than a personal setting.
- `README.md` carries both the presets' minimum floors and a "Verified with" block naming the
  stack this repo actually builds against. The floors are about the presets; the verified block
  must track `build.gradle.kts` and the wrapper — update it when a Dependabot bump lands.

### Testing Requirements
No tests. Verify a preset change with `./gradlew --stop && ./gradlew build` and confirm the daemon
starts with the expected JVM args.

## Dependencies

### Internal
- Writes `../gradle.properties`, which drives every Gradle invocation in the repo

<!-- MANUAL: -->
