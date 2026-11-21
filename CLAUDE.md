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

## Working notes: Subscriptions & Bills (0.127 → 0.137, Nov 2026)
Where the work lives: `MMbank-SUBSCRIPTIONS-PLAN.md` (phases 1-8, all delivered), per-release entries at the end of `MMbank-FEATURE-LOG.md`.

**Workflow lessons**
- CI is the only verification. After a push, wait for the run to finish before pushing again (`cancel-in-progress` loses the result). A red run with "Plugin ... was not found" (KSP/Gradle) is infrastructure: re-run the failed jobs once the run has completed, don't change code.
- The user installs the dev APK from the direct link `https://github.com/xemmy/mmbank/releases/download/dev-claude-mmbank-local-ai-parsing-4cfi14/MMbank-dev.apk` (refreshed by every green push to `claude/**`) and reports from the in-app **`?` dialog** (Subscriptions → "Why isn't it detected?"). Detection quality is tuned from those screenshots, not guessed.
- No local Gradle: hand-reason test fixtures. Subscription/bill fixtures break easily on exact gaps (weekly/bi-weekly now need 4 charges and gaps within 1 day) and on merchant names that match the never-subscription patterns.
- A new Room column must also be added to `MigrationSqlTest.addedColumns` (stripped from the v25 baseline) and the schema file/version strings in that test bumped; new tables go in `addedTables`.

**Subscription model (do not undo)**
- Rows are keyed by id, not merchant; several rows per merchant (even identical twins) are fine. Links are `(transactionId, subscriptionId)`. A row with any link, or `isManual`, is user-owned: rescan recomputes it from its own charges and never deletes it. `SubscriptionRescan.plan` is pure; detection (`SubscriptionRadarEngine`, v3) sees only unlinked charges.
- Detection is deliberately conservative. Blocklist `domain/intelligence/NeverSubscription.kt` (rides, delivery, groceries, fuel, outings, restaurants, beauty, clinics, telecom/utility billers); user "Never" keys live in `AppPreferences.neverSubscriptionKeys` (NOT in backups). Two-charge "possible" needs the same day of month unless the SMS says subscription/renewal. Add merchant spellings to the pattern lists when the `?` dialog shows a miss.

**Bills (telecom/utilities)**
- du, e&/Etisalat, DEWA etc. are bills, not subscriptions: `BillDetector` buckets payments by calendar month (amount and day are free), `BillAutoMatch` marks a tracked bill paid from the captured SMS. Providers listed in `BillDetector.PROVIDERS`; "DU Google Payment" is intentionally NOT a biller.
- One payment can arrive as the bank deduction SMS **and** the provider receipt (which names the phone line). `CaptureDedupPolicy.isBillPair` merges them (24 h window), the line is kept in `transactions.billRef`; two number-less bank SMS stay two payments (the user's second line). `BillDuplicateFinder` + the Bills "Merge" card clean up old doubles.
- Open question to verify with the user's real SMS: that du's receipt SMS parses to a merchant `BillDetector` recognises.

**Other delivered pieces worth knowing**
- Quick "Split with Tabby ×X" (`PayLaterSplit`, `PayLaterPlan.sourceIsInstallment`, `PayLaterAutoMatch`); merchant rename everywhere with future aliases (`merchant_aliases`, Settings → Merchant renames); charge search (`ChargeFilter`); subscription totals/groups (`SubscriptionTotals`, `SubscriptionGroups`); Undo snackbar via `MMScaffold(snackbarHost)`.
- Not done: "Split into 2" for a twins row, per-account FX tolerance, scan after bulk SMS import, Pay Later editor switch for `sourceIsInstallment`, "Matched to …" line in the detail sheet.
