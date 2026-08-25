<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# .github

## Purpose
Dependabot configuration and the three GitHub Actions workflows. There is no CI job that gates
`main` — see the coverage gap below.

## Key Files

| File | Description |
|------|-------------|
| `dependabot.yml` | Weekly updates for `gradle` and `github-actions`, 5 open PRs max each |
| `workflows/security.yaml` | Submits the resolved Gradle dependency graph to GitHub |
| `workflows/lint.yaml` | `./gradlew ktlintCheck` |
| `workflows/simple_test_action.yaml` | `./gradlew test` with `dorny/paths-filter` gating |

## For AI Agents

### Trigger matrix — note what is *not* covered

| Workflow | Triggers |
|----------|----------|
| `security.yaml` | push to `main`/`master`, weekly cron (Mon 06:00 UTC), `workflow_dispatch` |
| `lint.yaml` | push to `feature/*`, `feat/*`, `features/*` with changes to `src/**`, `test/**`, `*.gradle.kts` |
| `simple_test_action.yaml` | same branch patterns, plus `gradle/**` |

**Tests and lint never run on `main` and never on pull requests.** A commit pushed straight to
`main` — or a PR merged without a feature-branch push — is completely unverified by CI. Run
`./gradlew build` locally before pushing to `main`.

### security.yaml
`gradle/actions/dependency-submission` uploads the resolved dependency graph so Dependabot can
raise vulnerability alerts against Gradle dependencies; GitHub cannot parse `build.gradle.kts`
natively. It needs two things that are easy to break:

1. **Dependency graph must be enabled** on the repository (Settings → Advanced Security).
   Without it the step fails with `The Dependency graph is disabled for this repository`.
2. **`permissions: contents: write`**, and the run must not be triggered by a Dependabot PR —
   Dependabot-initiated runs get a read-only `GITHUB_TOKEN` regardless of the `permissions` block,
   so submission would 403. This is why there is no `pull_request` trigger; do not add one. The
   graph is tracked per default branch, so submitting from a PR head has no value anyway.

### Working In This Directory
- Action versions are managed by Dependabot. When a bump PR touches a workflow, merge it rather
  than hand-editing the `uses:` pin.
- All three workflows set up JDK 25 Temurin with `cache: "gradle"` and `chmod +x gradlew`. Keep
  new workflows consistent with that shape.
- `simple_test_action.yaml` runs `dorny/paths-filter` and then re-checks the outputs in a shell
  step — the `paths:` trigger and the filter are redundant today but harmless.

### Testing Requirements
Workflow changes can only be verified by pushing. `security.yaml` has `workflow_dispatch`, so it
can be run on demand from the Actions tab; the other two require a push to a matching branch.

## Dependencies

### External
- `actions/checkout@v7`, `actions/setup-java@v5`, `actions/upload-artifact@v7`
- `gradle/actions/dependency-submission@v6`, `dorny/paths-filter@v4`

<!-- MANUAL: -->
