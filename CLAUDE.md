# MMbank
Android personal-finance app. Kotlin, Jetpack Compose, Room, MVVM.

## Environment — CRITICAL
- Verification happens ONLY via GitHub Actions
  (.github/workflows/test-build.yml) after pushing to the remote branch.
- Workflow: write code + pure-JVM unit tests -> push -> read CI results.

## What CI actually runs (.github/workflows/test-build.yml)
Triggered on push to `main` and `claude/**`, on pull requests to `main`, and
by manual dispatch. Three jobs:

1. **lint-and-unit-tests** — runs `./gradlew :app:testDebugUnitTest --no-daemon -x lint`.
   A failing (or non-compiling) test fails this job. On failure, the
   "Summarize failing tests" step lists each failing `Class.method: message`
   in the log and the run summary. Despite the job name, lint never runs
   (`-x lint` skips it). HTML reports and XML results are uploaded as the
   `unit-test-results` artifact.
2. **build-apk** — runs even when tests failed (`if: !cancelled()`), so a red
   commit's APK can still be inspected. Runs `./gradlew :app:assembleDebug`
   (up to 4 retry attempts with clean/daemon-stop/full-reset), verifies
   `app-debug.apk` exists, uploads it. On a push to `main` it publishes a
   GitHub Release — only if the unit tests passed. Also attempts
   `:app:assembleRelease` (continue-on-error, non-gating).
3. **final-status** — fails the workflow unless BOTH the unit tests and the
   APK build succeeded. Skipped when the run is cancelled: a newer push to
   the same ref cancels the running one (`cancel-in-progress`), so wait for a
   push's run to finish before pushing again, or its result is lost.

**Consequence: a green workflow means the unit tests passed AND the APK
built.** Beyond MigrationSqlTest (migration SQL on real SQLite via
sqlite-jdbc), it proves nothing about Compose rendering, navigation,
notifications, permissions or anything else needing the Android framework —
there is no instrumented/Robolectric suite — so list those under "Not covered
by CI" in the feature log.

## Conventions
- Room schema changes: bump `version` in data/local/AppDatabase.kt and add an
  explicit Migration object with raw SQL. Never use destructive migration.
- Domain models (domain/model/Models.kt) mirror entities
  (data/local/entity/Entities.kt) via toEntity()/toDomain() mappers.
  Any field added to one must be added to all three, with a mapper
  round-trip unit test.
- Every new piece of logic gets a pure-JVM unit test in the same commit.
- Bump the app version on every functional update: `versionCode`/`versionName`
  in app/build.gradle.kts. Increment `versionCode` by 1 each time; move
  `versionName` by patch for fixes (0.3.0 -> 0.3.1), minor for new features
  (0.3.1 -> 0.4.0). Do it in the same commit as the change. Pure test/docs/CI
  edits with no APK-behavior change don't need one.
- MMbank-PHASED-UPGRADE-PLAN.md (Phases 0-6) is complete — see
  MMbank-HANDOVER.md for that state. Further work proceeds directly per user
  request, not phase-gated; the conventions on this page still apply to it.
- Settings toggles come in two shapes, pick deliberately: a plain
  AppPreferences boolean re-read inside the one ViewModel's combine (e.g.
  hideZeroBalances) when only that ViewModel needs it; a reactive StateFlow
  on AppContainer (e.g. hideBalances) when multiple independent
  ViewModels/screens must reflect the change live.
