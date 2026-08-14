# MMbank — Feature Log

Running record of feature work landed after the Phases 0-6 upgrade plan (see
`MMbank-HANDOVER.md` for that earlier state). One entry per phase, written in
the same commit as the code and amended once CI reports the real result.

Why this file exists: CI proves compilation only, several sessions have worked
this branch concurrently, and chat scrollback is not a durable record. Anything
worth knowing later belongs here and in git history.

---

## Phase 1 — Per-category budgets & cap alerts

**Status:** landed, CI green (commit `6f500e4`)
**Version:** `versionCode = 37`, `versionName = "0.24.0"`
**Migration:** none — the `monthlyCap` column already shipped

### Why

`Category.monthlyCap` was dead storage. It was captured when creating a
category (`ui/components/CategoryPickerBottomSheet.kt`), persisted, and carried
its own shipped migration — but **no screen ever read it back**, so a cap you
set did nothing. There was also no way to edit, re-cap, or delete a category at
all: `CategoryRepository.delete()` had zero callers.

This phase makes the cap visible, editable, and enforced.

### What changed

**Domain**
- `domain/budget/CategoryBudgetCalculator.kt` (new) — pure, JVM-testable.
  - `monthToDateByCategory(...)` — per-category spend for one currency over a
    half-open month window. Skips CREDIT rows, `excludeFromStats` rows, and
    uncategorized rows, so it agrees with every other spend total in the app.
  - `statusFor` / `statuses` — `UNDER` / `NEAR` (≥80%) / `OVER` per category,
    sorted closest-to-cap first. A null or ≤0 cap reads as *no budget* rather
    than a budget you instantly blow past.
  - `alertKey` / `pendingAlert` — one alert per category per threshold per
    month. Keys embed their own month, so a new month resets with no reset job.

  Scoped to a single currency throughout, mirroring the existing pacing check's
  `sumDebitsInRange(transaction.currencyCode, …)`: a cap is denominated in one
  currency, so summing mixed-currency rows into it would compare dirhams
  against dollars.

**Data**
- `data/local/dao/Daos.kt` — added `sumDebitsInRangeForCategory(...)`, a
  targeted aggregate rather than loading the month's rows, because the alert
  check runs on the capture path for every debit.
- `data/prefs/AppPreferences.kt` — added `categoryBudgetAlerts: Set<String>`
  for alert dedupe, pruned to the current month on write so it can't grow
  without bound.

**DI / capture path**
- `di/AppContainer.kt` — `onTransactionRecorded` now fans out to both
  `checkDailyBudgetPacing` and the new `checkCategoryBudget`. The new check is
  fire-and-forget under `runCatching` like every other post-insert side effect,
  so it can never affect the ingest result. Month keys are formatted with an
  explicit `Locale.US` — the default locale would emit Arabic-Indic digits on
  an Arabic device and key the same month two different ways.

**UI**
- `ui/screens/budgets/CategoryBudgetsScreen.kt` (new) — screen + ViewModel +
  `CategoryBudgetSummaryCard`. Progress bar per capped category, an
  edit dialog (name / icon / color / cap), delete with confirmation — which is
  what finally **wires up the unused `CategoryRepository.delete()`**.
- `ui/AppViewModelProvider.kt` — registered `CategoryBudgetsViewModel`. A
  missing initializer here is exactly what crashed `ReviewViewModel`, so this
  is deliberately not left to the end.
- `ui/MainActivity.kt` — new `category_budgets` route.
- `ui/screens/insights/InsightsScreen.kt` — doorway row plus the summary card.

  The card takes its **own** `CategoryBudgetsViewModel` rather than reading
  `DashboardViewModel`: that one's `combine` is already at Kotlin's 5-argument
  limit and folds state through a bundle class to fit, so widening it for
  independent data would have made an existing workaround worse.

### Tests

`app/src/test/.../domain/budget/CategoryBudgetCalculatorTest.kt` — 18 tests:
- Aggregation: sums per category; CREDIT rows excluded; `excludeFromStats`
  excluded; uncategorized skipped; a foreign currency never summed into the
  cap's currency; the month window is half-open at both ends.
- Thresholds: 79.99% is UNDER, exactly 80% is NEAR, exactly the cap is OVER;
  overspend reports zero remaining, never a negative.
- Caps: null, zero and negative caps all read as "no budget".
- Alerts: UNDER never alerts; a threshold fires once then goes quiet; NEAR then
  OVER fires twice with distinct keys; a new month alerts again.

### Deferred

- The cap is compared against spend in the **default currency only**. A
  category charged in a second currency is not converted into the cap's
  currency — consistent with the existing pacing check, but worth revisiting if
  multi-currency budgeting is wanted.
- Category *creation* still happens in the picker bottom sheet; this screen
  edits and deletes but does not add.

### CI verification

Run `31767562781`, job `94666447671` (`Lint & Unit Tests`) — read from the job
log rather than the checkmark, which `continue-on-error` masks green:

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2m 11s
25 actionable tasks: 25 executed
```

No `FAILED` task and no `e: file://` compiler errors — only pre-existing
deprecation warnings from `SettingsScreen.kt` / `TransactionsScreen.kt`.
`assembleDebug` passed in the same run on attempt 1 (no retries), APK
verified and uploaded.

---

## Phase 2 — Subscription radar: automatic, with price-change detection and reminders

**Status:** landed, CI green (commit `041e7af`)
**Version:** `versionCode = 38`, `versionName = "0.25.0"`
**Migration:** `MIGRATION_17_18`, DB `version = 18`

### Why

Three gaps, one of them a real bug:

1. **A price rise made a subscription silently disappear.**
   `SubscriptionRadarEngine` required every charge to sit within 5% of the
   series average. When Netflix went 39.99 → 56.00, the whole series failed
   that check and the subscription vanished from the radar — at exactly the
   moment the user most wants to be told. This was the motivating find.
2. Detection only ever ran when the user remembered to tap **Scan**.
3. Renewal dates were displayed but never reminded anyone.

### What changed

**Domain**
- `SubscriptionRadarEngine.kt` — restructured. Cadence is now checked first
  (it must hold either way), then: a steady price takes the flat path as
  before; a varying price falls through to `detectWithPriceChange`, which
  splits the series at the last amount change. The older run must be
  internally steady to qualify, so *three different amounts in a row* is still
  correctly rejected as erratic spending rather than reported as a price rise.
  One charge at the new price is enough, since a rise is normally noticed at
  the first renewal carrying it.
- `SubscriptionReminders.kt` (new) — pure: which subscriptions are worth
  reminding about, and one-announcement-per-renewal dedupe. Keys embed the due
  date (`"{id}:{nextDueDateMillis}"`) so the next cycle re-arms by itself when
  detection moves the date forward — no expiry job.

**Data**
- `Subscription` / `SubscriptionEntity` / both mappers — added
  `previousAmount: BigDecimal?`. `MIGRATION_17_18` adds it as nullable `TEXT`,
  matching how every BigDecimal column is stored (see `Converters`).
- `Daos.kt` — added `findRealDebitsByMerchant(...)`, so re-detection after a
  capture reads one merchant's history instead of the whole ledger.
- `AppPreferences` — `subscriptionRemindersEnabled` (default **off**) and
  `subscriptionRemindersSent` for reminder dedupe.

**Background**
- `SubscriptionReminderWorker.kt` (new) — the app's **first and only** periodic
  work. See the tension note below.
- `AppContainer` — `refreshSubscriptionForMerchant` on the existing
  post-insert hook (fire-and-forget, one-merchant scope), plus
  `setSubscriptionRemindersEnabled` / `ensureSubscriptionReminderWork`.
  `MMBankApp` re-arms on start (unique work + `KEEP`, so idempotent).

**UI**
- `SubscriptionsScreen` — "↑ was 39.99" / "↓ was 25.00" badge, colored by
  direction. Fixed two doc comments that claimed detection never runs in the
  background, which this phase made untrue.
- `SettingsScreen` — the reminders toggle.

### The one real tension, and how it was resolved

The README sells **"There is no background polling"** as a battery property,
and the app genuinely had zero periodic work. Renewal reminders need a daily
tick, so this phase could not both add them and leave that claim untouched.

Resolved by making it strictly opt-in: the toggle defaults to **off**, nothing
is scheduled until the user turns it on, and the tick does a single local DB
read with no network. Anyone who doesn't want reminders keeps the old behavior
exactly. This is why the toggle is a plain `AppPreferences` boolean rather than
a reactive `AppContainer` flow — only Settings and the worker read it.

### Tests

- `SubscriptionRadarTest` — 7 new: price rise reported with the old price;
  price drop likewise; cadence and next-due survive a change; a steady price
  reports null; a change on an irregular schedule still rejected; three
  different amounts still rejected as erratic; a rise after only one charge at
  the old price counts. The pre-existing "variance beyond 5 percent is not
  detected" test still passes unchanged — verified the new path rejects it via
  the older-run consistency check rather than by accident.
- `SubscriptionRemindersTest` — 10: window in/out, overdue still reminds,
  dismissed never reminds, announced-once, next-cycle re-arm, ordering,
  `daysUntil` flooring and negatives, `prune`.
- `EntityMappersTest` — `previousAmount` round-trip in both states.

### Deferred

- Reminder window is fixed at 3 days; not user-configurable.
- A price change is detected only at the *last* amount change. A subscription
  that changed price twice reports the most recent change only.

### CI verification

Run `31768160891`, job `94668248682` (`Lint & Unit Tests`) — read from the job
log, not the `continue-on-error`-masked checkmark:

```
> Task :app:kspDebugUnitTestKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3m 14s
25 actionable tasks: 25 executed
```

No `FAILED` task, no `e: file://` errors. Worth noting for a migration commit:
Room's KSP processor validates `@Entity` classes at compile time, so
`kspDebugKotlin` completing clean is real evidence that `SubscriptionEntity`'s
new `previousAmount` column is consistent — a schema/entity mismatch would have
failed the build here rather than at runtime. `assembleDebug` passed in the
same run.

---

## Phase 3 — Monthly report

**Status:** landed, CI green after a fix (commits `7aaad9f` + `959f74f`)
**Version:** `versionCode = 39`, `versionName = "0.26.0"`
**Migration:** none

### Why

Data could leave the app only as raw CSV or an encrypted `.mmb` blob — nothing
a human reads. Meanwhile `NetWorthAggregator` sat fully written and unit-tested
with **zero production callers**: it computes an assets-vs-liabilities split
with per-account contributions, while the Dashboard rolled its own flat total
inline. This phase gives the app a readable statement and finally uses that
engine.

### What changed

**Domain (both pure, both JVM-testable)**
- `domain/report/MonthlyReportBuilder.kt` (new) — assembles a `MonthlyReport`
  from data the caller already loaded: income/spend/net with month-over-month
  deltas, **the net-worth breakdown via the now-wired `NetWorthAggregator`**,
  top categories with share-of-spend, top merchants, active subscriptions with
  a monthly-equivalent total, goals, and health metrics.
  - Scoped to one currency: rows in others are left out rather than summed in,
    since the report has no exchange rates. Same stance as Phase 1's caps.
  - Uncategorized spend is shown rather than dropped, so the category rows add
    up to the headline figure — pinned by a test.
  - Weekly subscriptions are normalized to monthly using the detector's own
    `SubscriptionCadence.nominalDays` (30/7), not a hand-picked 4.
- `domain/report/MonthlyReportHtmlFormatter.kt` (new) — `MonthlyReport -> String`.

**Why HTML and not PDF.** No new dependency; it opens in any browser and prints
to PDF from there; and decisively, it keeps this a pure function CI can
actually test. A PDF renderer needs `android.graphics`, which this project's
JVM-only unit tests cannot exercise — the format choice is what makes the
output testable at all.

**UI**
- `ui/screens/report/MonthlyReportScreen.kt` (new) — month picker (never
  advances past the current month), the rendered report, and export via SAF
  `CreateDocument("text/html")`, reusing the launcher shape already used for
  the CSV export in Settings → Backup.
- Registered in `AppViewModelProvider`; `monthly_report` route; Insights
  doorway row.

The ViewModel bundles its five ledger flows into a `ReportInputs` record and
combines that with the selected month — the same workaround `DashboardViewModel`
uses, because Kotlin's `combine` tops out at five typed arguments and the report
needs six inputs. Export status is a separate `StateFlow` rather than part of
`uiState`, so a transient message can't re-run report assembly.

### Tests

- `MonthlyReportBuilderTest` — 14: headline figures; excluded rows and foreign
  currency left out; deltas present and absent; an empty month produces a
  report rather than crashing; category ranking, share, and the
  uncategorized-rows-add-up invariant; merchant ranking with counts; net worth
  wired through the aggregator; weekly→monthly normalization; dismissed and
  foreign-currency subscriptions excluded; archived goals excluded.
- `MonthlyReportHtmlFormatterTest` — 12, including **escaping**: a merchant
  named `<script>alert('x')</script>` must not survive as markup. Merchant and
  category names come from captured bank messages, so this is untrusted input
  reaching a document. Also: empty sections omitted, delta direction (a spend
  rise is bad, an income rise is good), price-change flag, goal percentages.

### Docs

`MMbank-HANDOVER.md` refreshed — it claimed `version = 9` while the schema is
at 18. Added a staleness banner, corrected §3 to describe the real chain
(`MIGRATION_1_2` … `MIGRATION_17_18`), and widened the accepted-gap note: the
migration gap now spans all 17 steps, KSP validates entities but never executes
the `ALTER TABLE`s, and `app/schemas/` still isn't committed.

### Deferred

- Report currency is the default currency; no multi-currency consolidation.
- Export writes a file; there's no direct share-sheet intent.

### CI verification

**First run failed, and the failure was a real bug.** Run `31768756725`, job
`94670053305`:

```
MonthlyReportHtmlFormatterTest > ampersands in names are escaped FAILED
    java.lang.AssertionError at MonthlyReportHtmlFormatterTest.kt:77
666 tests completed, 1 failed
BUILD FAILED in 2m 35s
> Task :app:testDebugUnitTest FAILED
```

Note the trap this is the textbook case of: the **job's conclusion was
`success`** and the workflow was green, because the step is
`continue-on-error: true`. Only the log says otherwise. Nothing here would have
been noticed by trusting the check mark.

The cause: `appendCategories` builds its own `<span>` markup, so it renders
through `appendRow`'s `rawLabel` path, which skips escaping — and the category
name was interpolated raw. Merchants, subscriptions and goals escaped
correctly, so the `<script>` test passed while the category path stayed open.
Fixed in `959f74f` by escaping the name, plus markup tests on all three
remaining `rawLabel` paths so the next row to grow a `<span>` can't quietly
reintroduce it. Formatter test count 12 → 15.

`assembleDebug` passed in the first run regardless (attempt 1, no retries) —
the bug was behavioral, not a compile error, which is precisely why the
compile-only signal was not enough.

**Verified green after the fix.** Run `31776011546`, job `94691510712`:

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 18s
25 actionable tasks: 13 executed, 12 from cache
```

`testDebugUnitTest` carries no `FROM-CACHE` / `UP-TO-DATE` marker, so the suite
genuinely re-ran rather than being served from the build cache. Note that a
passing run prints **no** `N tests completed` line at all — Gradle only emits
that summary when something fails, so the disappearance of
`666 tests completed, 1 failed` is the pass signal here, not an absent count.

`assembleDebug` also passed in the same run (job `94691781070`): attempt 1, no
retries, with `Verify build success`, `Check APK exists` and `Upload Debug APK`
all green.

### A second CI trap, worth writing down

Between the fix and this verification, GitHub reported two `Final Status`
**failures** on `959f74f`. Both were false alarms: pushing the docs-only commit
`3bbe971` fifteen seconds later cancelled the in-flight runs, and
`Final Status` fails on `cancelled` because its check is
`build-apk result == success`. The tell was that `Lint & Unit Tests` died
during *JDK setup*, before running a single test.

Two things make this noisier than it looks: the repo fires **two runs per
push** (a `push` trigger and a `pull_request` trigger, via the open PR #9), so
each supersede emits two failure notifications. Nothing was wrong and nothing
was changed — worth recognizing rather than "fixing" next time.

---

## Phase 4 — Merchant Detail screen

**Status:** landed, CI pending
**Version:** `versionCode = 40`, `versionName = "0.27.0"`
**Migration:** none

### Why

`FinancialCopilotQueryHandler` builds per-merchant totals and `CategoryRepository.learnMapping`
already backfills every still-uncategorized transaction by merchant, but no
screen ever let you open a single merchant and see its history. This phase
adds that drill-down, reusing engines the app already has rather than adding
new ones.

### What changed

**Domain**
- `domain/intelligence/MerchantProfiler.kt` (new) — pure, JVM-testable.
  `profile(transactions, merchant)` scans real (non-demo, non-excluded) DEBIT
  charges matching the merchant (case-insensitive, trimmed — the same key
  `SubscriptionRadarEngine` groups on), scoped to the merchant's most common
  currency so a merchant charged in two currencies isn't averaged across both.
  Returns total, count, average, min/max, first/last seen, and a
  month-bucketed series (sorted, correct across a year boundary) for a trend
  view. Returns null for a merchant with no matching history.

**UI**
- `ui/screens/merchant/MerchantDetailScreen.kt` (new) — screen + ViewModel.
  Stat cards (total/count/average/range/first-last-seen), a subscription-radar
  flag when `SubscriptionRepository.observeActive()` currently lists the
  merchant, a simple bar-per-month spend trend (matching the existing
  Box/Column `ChartBar` pattern already used on Insights' cash-flow card,
  rather than introducing a new Canvas chart type for one screen), and a
  one-tap re-categorize via `CategoryPickerBottomSheet` that calls the
  existing `CategoryRepository.learnMapping`.
- `ui/AppViewModelProvider.kt` — registered `MerchantDetailViewModel` with
  `createSavedStateHandle()`, mirroring `RuleEditorViewModel`'s nav-arg
  pattern (a missing initializer here is exactly what crashed `ReviewViewModel`).
- `ui/MainActivity.kt` — new `merchant_detail/{merchant}` route, `NavType.StringType`.
- Entry points: the merchant name in `TransactionDetailSheet` is now
  clickable (`onMerchantClick`), and a subscription row in `SubscriptionsScreen`
  is now a full clickable row rather than only its dismiss button.
  `TransactionsScreen` and `SubscriptionsScreen` both grew an
  `onOpenMerchant: (String) -> Unit` parameter to carry the navigation intent
  up to `MainActivity`, which URL-encodes the merchant name into the route
  path segment. Navigation Compose decodes it once on the way back into the
  `SavedStateHandle`, so the ViewModel reads it as-is rather than decoding
  again (a double-decode would have mangled a merchant name containing a
  literal `%`).

  Not added as an Insights doorway row: unlike Analytics/Subscriptions/Goals,
  this is a per-merchant drill-down rather than a standalone feature list
  item, so it's only reachable from an actual merchant.

### Tests

`app/src/test/.../domain/intelligence/MerchantProfilerTest.kt` — 12 tests:
aggregation (total/count/average/min/max); no match and empty input both
return null; case-insensitive/trimmed merchant matching; CREDIT rows,
`excludeFromStats` rows, `isDemo` rows and other merchants all excluded;
dominant-currency selection leaves the minority currency out; a single
transaction reports itself as its own min/max/average; first/last-seen
tracks the true earliest/latest timestamp regardless of input order; monthly
series buckets and sorts correctly across a year boundary (Dec 2025 before
Jan 2026).

### Deferred

- The monthly trend is a simple bar-per-month view, not a full sparkline
  chart component — there was no existing Canvas line-chart in the codebase
  to build on (checked: `CanvasCharts.kt` has only the pacing arc gauge), and
  one screen didn't justify introducing a new chart primitive.

### CI verification

Pending — will be recorded once the push is read from the Actions log rather
than the checkmark, per this repo's standing rule that `continue-on-error`
masks real test failures.

Between the fix and this verification, GitHub reported two `Final Status`
**failures** on `959f74f`. Both were false alarms: pushing the docs-only commit
`3bbe971` fifteen seconds later cancelled the in-flight runs, and
`Final Status` fails on `cancelled` because its check is
`build-apk result == success`. The tell was that `Lint & Unit Tests` died
during *JDK setup*, before running a single test.

Two things make this noisier than it looks: the repo fires **two runs per
push** (a `push` trigger and a `pull_request` trigger, via the open PR #9), so
each supersede emits two failure notifications. Nothing was wrong and nothing
was changed — worth recognizing rather than "fixing" next time.

---

## Phase 5 — Transaction retention prune (completing phase-ai-c)

**Status:** landed, CI green
**Version:** `versionCode = 41`, `versionName = "0.28.0"`
**Migration:** none — deletes existing rows, adds no columns

### Why

`phase-ai-c` (commit `b66cf01`) shipped `AiRetentionScorer` — pure scoring
for "is this transaction significant enough to protect from pruning" — but
explicitly left it unwired: *"not wired into a scheduled pruning job because
MMbank has no such job today... this ships the evaluation logic ready for
that future feature."* No such job existed anywhere; `BackupManager` only
exported/imported. This phase builds the job, opt-in and off by default,
per an explicit user decision (asked directly rather than assumed, since
auto-deleting rows in a finance app is destructive and hard to reverse).

### What changed

**Domain**
- `domain/ai/TransactionRetentionPlanner.kt` (new) — pure, JVM-testable.
  `plan(transactions, retentionMonths, nowMillis)` returns the transaction
  IDs older than the cutoff that `AiRetentionScorer.isSignificant` does NOT
  protect. The anomaly average that scorer needs is computed here, per
  currency (a cap denominated in one currency can't be compared against
  another — same rule `CategoryBudgetCalculator` follows), from every real
  debit in the input, not just the rows old enough to be candidates, so the
  bar a transaction must clear doesn't drift downward as earlier runs prune
  history out from under it. `retentionMonths <= 0` short-circuits to an
  empty plan without scanning, so a disabled setting can never delete
  anything. `cutoffMillis` is computed in UTC so the result doesn't depend
  on the device's timezone.

**Data**
- `domain/repository/Repositories.kt` / `TransactionRepositoryImpl.kt` —
  added `getAllReal()`, a one-shot snapshot of the real (non-demo)
  partition via the existing `dao.getAll()` (already used by
  `BackupManager`, already excludes demo rows). Deliberately not
  `observeTransactions()`: that flow follows the live Demo-mode toggle, and
  a background job must never evaluate — or delete — demo rows depending on
  what the UI happens to be showing. Deletion itself reuses the existing
  `deleteMany(ids)` (already backing multi-select delete in Activity).
  Two test doubles (`IngestCapturedMessageUseCaseTest`,
  `BulkUpdateTransactionsUseCaseTest`) got the new override.

**Prefs / DI**
- `data/prefs/AppPreferences.kt` — `transactionRetentionMonths: Int`
  (default `0` = off), following the existing `appLockTimeoutMinutes`
  int-pref shape.
- `data/retention/TransactionRetentionWorker.kt` (new) — a weekly
  `CoroutineWorker`, structurally identical to `SubscriptionReminderWorker`:
  reads the setting, no-ops at `<= 0`, otherwise loads the real snapshot,
  runs the planner, and deletes the planned IDs under `runCatching` so a
  failure can never produce a partial or repeated deletion. This is
  MMbank's **second** periodic work item; every doc comment that used to
  call `SubscriptionReminderWorker` "the app's only periodic work"
  (`AppContainer`, `AppPreferences`, `SettingsScreen`, the worker itself)
  was updated to say "one of" instead, rather than leaving a claim that's
  now false sitting in the code.
- `di/AppContainer.kt` — `setTransactionRetentionMonths` /
  `ensureTransactionRetentionWork` / `cancelTransactionRetentionWork`, an
  exact mirror of the existing subscription-reminder scheduling trio
  (unique periodic work, `ExistingPeriodicWorkPolicy.KEEP`, so re-arming on
  every app start doesn't reset the 7-day period). Wired into
  `MMBankApp.onCreate` next to `ensureSubscriptionReminderWork()`.

**UI**
- `ui/screens/settings/SettingsScreen.kt` — a "Transaction history" section
  in the existing Backup & restore subpage (both are about how much history
  the ledger carries), a `FilterChip` row over
  `TRANSACTION_RETENTION_OPTIONS_MONTHS` (`0/6/12/24/36` → "Forever/6 mo/1
  yr/2 yr/3 yr"), mirroring the Security subpage's app-lock-timeout chip
  row exactly.

### Tests

`TransactionRetentionPlannerTest` — 10 cases: retention off prunes nothing;
rows inside the window are always kept; an ordinary old row is pruned; an
old anomalously-large debit is protected; an old INCOME row is protected
regardless of amount; an old `excludeFromStats` correction is NOT protected
(matches `AiRetentionScorerTest`'s existing case) and gets pruned; a large
old AED history can't shield a small USD anomaly from getting wrongly
spared (proves per-currency isolation, not just per-currency labeling —
constructed so a currency-blind average would give the opposite answer); a
CREDIT row is never averaged in and can itself be pruned; `cutoffMillis`
moves back exactly the configured months in UTC; an empty transaction list
plans nothing.

### Deferred

- No in-app preview of what a prune run *would* delete before it runs —
  the user chose the always-on weekly job over a manual "preview and
  confirm" flow when asked directly which shape this phase should take.
- No CSV/backup-export prompt before the first prune. `BackupSettingsScreen`
  is one scroll away in the same subpage, and the section's copy says
  "back up first if you want to keep everything," but there's no forced
  gate.

### CI verification

**Verified green on `fb82f91`.** Run `31797861274`:

- `Lint & Unit Tests` (job `94758822561`) — conclusion `success`. Read the
  "Run unit tests" step log directly rather than trusting the checkmark
  (this step has `continue-on-error: true`):
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 3m 14s
  25 actionable tasks: 25 executed
  ```
  No `N tests completed, N failed` line — per this file's established
  convention (see Phase 3), Gradle only prints that summary when something
  fails, so its absence is the pass signal, including the 10 new
  `TransactionRetentionPlannerTest` cases and the two updated repository
  test doubles.
- `Build APK (with retries)` (job `94759566788`) — conclusion `success`.
  Debug APK built on **attempt 1**, no retries needed; `Verify build
  success`, `Check APK exists`, and `Upload Debug APK` all green. The
  non-gating Release APK build also succeeded.
- `Final Status` (job `94760775195`) — conclusion `success`.
