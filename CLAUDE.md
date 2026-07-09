# MMbank
Android personal-finance app. Kotlin, Jetpack Compose, Room, MVVM.

## Environment — CRITICAL
- NO Android SDK in this sandbox. Gradle downloads are blocked (403).
  NEVER run gradle build/test/run locally.
- Verification happens ONLY via GitHub Actions
  (.github/workflows/test-build.yml) after pushing to the remote branch.
- Workflow: write code + pure-JVM unit tests -> push -> read CI results.

## What CI actually runs (.github/workflows/test-build.yml)
Triggered on push to `main` and `claude/**`. Three jobs:

1. **lint-and-unit-tests** — runs `./gradlew :app:testDebugUnitTest --no-daemon -x lint`.
   ⚠️ This step has `continue-on-error: true`: the job (and the whole workflow)
   stays green even if unit tests FAIL. Despite the job name, lint never runs
   (`-x lint` skips it). Test results are uploaded as the `unit-test-results`
   artifact.
2. **build-apk** — runs `./gradlew :app:assembleDebug` (up to 4 retry attempts
   with clean/daemon-stop/full-reset), verifies `app-debug.apk` exists, uploads
   it. Also attempts `:app:assembleRelease` (continue-on-error, non-gating).
3. **final-status** — fails the workflow iff build-apk failed.

**Consequence: a green workflow proves COMPILATION only. To verify tests, open
the "Run unit tests" step output (or the unit-test-results artifact) and check
for failures explicitly. Never declare a phase done off the green check mark
alone.**

## Conventions
- Room schema changes: bump `version` in data/local/AppDatabase.kt and add an
  explicit Migration object with raw SQL. Never use destructive migration.
- Domain models (domain/model/Models.kt) mirror entities
  (data/local/entity/Entities.kt) via toEntity()/toDomain() mappers.
  Any field added to one must be added to all three, with a mapper
  round-trip unit test.
- Every new piece of logic gets a pure-JVM unit test in the same commit.
- Work one phase at a time per MMbank-PHASED-UPGRADE-PLAN.md. Do not touch
  files outside the active phase's "Files touched" list.
