# MMbank
Android personal-finance app. Kotlin, Jetpack Compose, Room, MVVM.

## Environment — CRITICAL
- Verification happens ONLY via GitHub Actions
  (.github/workflows/test-build.yml) after pushing to the remote branch.
- Workflow: write code + pure-JVM unit tests -> push -> read CI results.

## What CI actually runs (.github/workflows/test-build.yml)
Triggered on push to `main` and `claude/**` (not for docs-only pushes: `paths-ignore: **/*.md`),
on pull requests to `main`, and by manual dispatch. Four jobs:

1. **lint-and-unit-tests** ("Unit Tests") — runs `./gradlew :app:testDebugUnitTest --no-daemon -x lint`.
   A failing (or non-compiling) test fails this job. On failure, the
   "Summarize failing tests" step lists each failing `Class.method: message`
   in the log and the run summary. Lint never runs (`-x lint` skips it).
   HTML reports and XML results are uploaded as the `unit-test-results` artifact.
2. **build-apk** — runs in parallel with the tests (no `needs`), so a red
   commit's APK can still be inspected. Runs `./gradlew :app:assembleDebug`
   (2 attempts: plain, then after `clean`), verifies `app-debug.apk` exists and
   uploads it as `MMbank-<versionName>-<versionCode>.apk` in the `mmbank-debug-apk`
   artifact (GitHub serves artifacts as a zip). There is no release build any more.
3. **publish** — only on a push when both jobs above succeeded. On `claude/**` it
   recreates the prerelease `dev-<branch with / as ->` with a direct `.apk`:
   `https://github.com/<owner>/<repo>/releases/download/dev-<slug>/MMbank-dev.apk`
   (the link is also at the top of the run summary). On `main` it publishes
   `v<versionName>` with `MMbank-v<versionName>.apk`, and skips with a warning if
   that tag already exists (version not bumped).
4. **final-status** — fails the workflow unless BOTH the unit tests and the
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
- Improvement tracker: MMbank-IMPROVEMENT-PLAN.md — mark items ✅ (with version) in the same commit that delivers them.
