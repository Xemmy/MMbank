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

**Status:** landed, CI green
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

**Verified green on `6a33a0f`.** Run `31786280991`:

- `Lint & Unit Tests` (job `94722792198`) — conclusion `success`. Read the
  "Run unit tests" step log directly rather than trusting the checkmark
  (this step has `continue-on-error: true`):
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 2m 55s
  25 actionable tasks: 25 executed
  ```
  No `N tests completed, N failed` line — per this file's established
  convention, Gradle only prints that summary when something fails, so its
  absence is the pass signal, including the 12 new `MerchantProfilerTest`
  cases.
- `Build APK (with retries)` (job `94723499852`) — conclusion `success`.
  Debug APK built on **attempt 1**, no retries needed; `Verify build
  success`, `Check APK exists`, and `Upload Debug APK` all green. The
  non-gating Release APK build also succeeded.
- `Final Status` (job `94724618231`) — conclusion `success`.

(Earlier this section incorrectly carried Phase 3's `959f74f`/PR #9
false-alarm note, pasted under the wrong heading — corrected above with
Phase 4's own run.)

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

---

## Phase 6 — Upcoming Bills & Reminders

**Status:** landed, CI green
**Version:** `versionCode = 42`, `versionName = "0.29.0"`
**Migration:** v18 -> v19, new `bills` table (see below)

### Why

`SubscriptionRadarEngine` already auto-detects recurring merchant charges
(weekly/monthly cadence, amount within 5%), so a real gap existed only for
what it structurally *can't* catch: rent, an EMI, a variable-amount utility
bill, or anything with no transaction history yet (asked directly rather
than assumed, since "loosen the detector" and "manual tracker" are
different features with different failure modes). The user picked the
manual tracker.

### What changed

**Domain**
- `domain/model/Models.kt` — `Bill` (name, amount, currencyCode, nullable
  `recurrence`, `nextDueDateMillis`, `createdAt`) and `BillRecurrence`
  (WEEKLY/MONTHLY/YEARLY). Null `recurrence` means one-off.
- `domain/intelligence/BillReminders.kt` (new) — pure, structurally
  identical to `SubscriptionReminders`: dedupe-by-`"{id}:{nextDueDateMillis}"`
  key, same "already overdue still reminds" stance, `pending`/`daysUntil`/`prune`.
- `domain/intelligence/BillAdvancer.kt` (new) — pure. `advance(bill)` decides
  what "mark paid" does: a recurring bill's due date moves forward exactly
  one cycle *from its current due date*, not from today (mirrors how
  `Subscription.nextDueDateMillis` projects from the last occurrence, not
  "now" — a bill paid late lands overdue-again rather than silently caught
  up, the same stance `BillReminders` takes). A one-off bill (`recurrence`
  null) returns null; the repository deletes it instead of persisting a
  paid one-off forever. There's no transaction signal to trust here the way
  `SubscriptionRadarEngine` has one, so nothing advances a bill except the
  user saying so.

**Data**
- `data/local/entity/Entities.kt` — `BillEntity` + `toDomain()`/`toEntity()`
  mappers (round-tripped in `EntityMappersTest`, per this repo's convention
  that every field added to model+entity gets a mapper test).
- `data/local/dao/Daos.kt` — `BillDao`: full CRUD (`insert`/`update`/
  `observeAll`/`getById`/`updateNextDueDate`/`delete`) rather than
  `SubscriptionDao`'s upsert-by-merchant shape, since every row here exists
  because the user added it, not because a scan detected it.
- `data/local/AppDatabase.kt` — schema v18 -> v19, `MIGRATION_18_19` creates
  `bills` (no unique index — unlike `subscriptions`, nothing here is
  deduped by name).
- `data/local/Converters.kt` — nullable `BillRecurrence` <-> TEXT converter.
- `domain/repository/Repositories.kt` / `data/repository/SimpleRepositories.kt`
  — `BillRepository` / `BillRepositoryImpl`. `markPaid(id)` defers entirely
  to `BillAdvancer`: advance-and-update, or delete on a one-off.

**Prefs / DI**
- `data/prefs/AppPreferences.kt` — `billRemindersEnabled` (default off,
  independent of `subscriptionRemindersEnabled` — a user may want one
  stream but not the other) and `billRemindersSent`.
- `data/bills/BillReminderWorker.kt` (new package) — daily `CoroutineWorker`,
  structurally identical to `SubscriptionReminderWorker`. MMbank's **third**
  periodic work item; every doc comment that enumerated the first two
  (`AppContainer`, `AppPreferences`, `SettingsScreen`, the other two workers)
  was updated to include this one rather than leaving a stale count.
- `util/BillNotificationHelper.kt` (new) — mirrors
  `SubscriptionNotificationHelper`; notification ID base `9300`, clear of
  the budget (9001/9100+) and subscription (9200+) families.
- `di/AppContainer.kt` — `billRepository`, `setBillRemindersEnabled` /
  `ensureBillReminderWork` / `cancelBillReminderWork`, an exact mirror of
  the subscription-reminder trio. Wired into `MMBankApp.onCreate`.

**Cash flow**
- `ui/screens/dashboard/DashboardViewModel.kt` — `computeFinancialHealth`'s
  mandatory-bills reservation ahead of payday now sums active subscriptions
  **and** manually tracked bills due in that window, each converted to the
  base currency independently before summing (same per-currency-then-sum
  order `CategoryBudgetCalculator` and the Phase 5 retention planner use).

**UI**
- `ui/screens/bills/BillsScreen.kt` (new) — list + add dialog (name, amount,
  "due in N days", a recurrence dropdown defaulting to Monthly, or
  one-off), a mark-paid and a delete action per row. Full CRUD in the
  dialog rather than a date picker, matching `GoalsScreen`'s existing
  minimal-input dialog style.
- `ui/screens/insights/InsightsScreen.kt` — new "Upcoming bills" doorway row.
- `ui/screens/settings/SettingsScreen.kt` — a "Bill due-date reminders"
  toggle next to the existing subscription-reminders one in Preferences.
- `ui/MainActivity.kt` / `ui/AppViewModelProvider.kt` — `bills` route,
  `BillsViewModel` registered.

### Tests

`BillRemindersTest` (7), `BillAdvancerTest` (4) — mirroring
`SubscriptionRemindersTest`'s cases (window in/out, overdue still reminds,
dedupe re-arms on the next cycle, soonest-first ordering, `daysUntil`
floors negative) plus `BillAdvancer`'s per-cadence advance and the one-off
null case. Two new `EntityMappersTest` cases: a recurring bill round-trip
and a one-off (null `recurrence`) round-trip.

### Deferred

- No due-date picker in the add dialog — "due in N days" avoids
  `DatePickerDialog` complexity for a first cut; `GoalsScreen`'s own
  `AddGoalDialog` skips a date picker for the same reason.
- No per-bill reminder lead-time or snooze; the shared 3-day window and
  the global on/off toggle are the only controls, matching
  `SubscriptionReminders.DEFAULT_WINDOW_DAYS`.
- `markPaid` has no undo. A recurring bill's due date can't be pushed back
  without a "un-mark-paid" action, which doesn't exist yet.

### CI verification

**First push (`35fec85`) had a real compile failure.** Run `31813493070`,
job `94809473700` (`Lint & Unit Tests`) reported conclusion `success` --
`continue-on-error: true` again masking the real result -- but the log
itself showed:

```
e: .../ui/screens/bills/BillsScreen.kt:124:13 This material API is
   experimental and is likely to change or to be removed in the future.
> Task :app:compileDebugKotlin FAILED
BUILD FAILED in 2m 14s
```

Kotlin treats an unguarded `@RequiresOptIn`-marked API as a hard compile
error, not a warning, despite the message text reading like one.
`BillsScreen` used `TopAppBar` without the `@OptIn(ExperimentalMaterial3Api::class)`
`GoalsScreen` already carries for the same API. `Build APK` (job
`94810186472`) failed outright on the same cause, so this one wasn't a
case where only the log-read caught it -- but the unit-test job's green
checkmark alone still would have hidden it, which is the exact trap this
file's standing rule exists for. Fixed and pushed as `b287662`.

**Verified green on `b287662`.** Run `31813931797`:

- `Lint & Unit Tests` (job `94811042108`) — conclusion `success`. Read the
  "Run unit tests" step log directly:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 1m 40s
  25 actionable tasks: 13 executed, 12 from cache
  ```
  No `N tests completed, N failed` line — per this file's established
  convention, Gradle only prints that summary when something fails, so its
  absence is the pass signal, including the new `BillRemindersTest`,
  `BillAdvancerTest`, and the two new `EntityMappersTest` bill round-trip
  cases.
- `Build APK (with retries)` (job `94811618418`) — conclusion `success`.
  Debug APK built on **attempt 1**, no retries needed; `Verify build
  success`, `Check APK exists`, and `Upload Debug APK` all green. The
  non-gating Release APK build also succeeded.
- `Final Status` (job `94813121143`) — conclusion `success`.

## Phase 7 — Monarch budgeting: category hierarchy, goal forecasting, 1Y chart filter

**Status:** landed, CI green
**Version:** `versionCode = 43`, `versionName = "0.30.0"`
**Migration:** v19 -> v20, `categories.parentCategoryId` column (see below)

### Why

A broad spec asked for a Monarch-style budgeting/forecasting layer plus a
from-scratch Splitwise-style group-expense system in one pass, on top of a
bottom-nav restructure. Surveyed the existing code first rather than
guessing: category budgets were already flat single-cap-per-category
(`CategoryBudgetCalculator`), `SavingsGoal` had no months-to-target
projection, the Insights chart already had a working date-filter row
(This month/3mo/6mo/YTD/All) just missing a trailing-12-months option, and
the group/split/IOU/debt/P2P-detection track had zero existing code
anywhere in the tree. Given the size, asked the user to pick a first slice
rather than attempting both tracks (and the nav restructure) at once; they
picked the Monarch track, with new screens reached as sub-screens (mirroring
how Goals and Bills are already reached from Insights) rather than an
immediate tab-bar change.

### What changed

**Domain**
- `domain/model/Category.kt` — `Category` gained `parentCategoryId: Long? =
  null`, for a two-level hierarchy (e.g. "Dining" filed under "Food"). Null
  stays a top-level category, so every existing category is unaffected.
- `domain/budget/CategoryBudgetCalculator.kt` — `rollUpSpend(categories,
  directSpendByCategoryId)`: widens each parent's total to include its
  children's spend (a child keeps its own unrolled total for its own
  status), so a parent's `monthlyCap` reads as a combined budget for itself
  and everything filed under it. Memoized DFS with a per-path cycle guard —
  a category on its own current recursion path contributes zero rather than
  recursing forever, since the pure function must stay safe even though the
  Edit-category UI already blocks creating a direct 2-cycle through its
  `candidateParents` filter.
- `domain/intelligence/GoalForecaster.kt` (new) — pure. `forecast(goal,
  averageMonthlyNetSavings, nowMillis)` rounds the remaining amount up to
  whole months at the given pace; an already-reached goal reports zero
  months due now, a non-positive average (spending at or above earnings, on
  average) reports an unknown projection (null/null) rather than an
  infinite or negative one. Deliberately takes the average as a parameter
  instead of recomputing it from transactions — `computeDashboardStats`
  already derives "average net flow of the last up-to-3 completed months
  with activity" (`DashboardStats.avgMonthlyNet`) for the Dashboard, and
  since that function is `internal` (module-visible, not file-scoped),
  `GoalsViewModel` reuses it directly rather than duplicating the
  history-averaging logic a second time.
- `ui/screens/dashboard/DashboardViewModel.kt` — `DashboardDateRange` gained
  `Last12Months`; `resolveDashboardWindow`/`dashboardPeriodLabel` handle it
  (this month and the eleven before it, "Last 12 months").

**Data**
- `data/local/entity/Entities.kt` — `CategoryEntity` gained
  `parentCategoryId: Long? = null`; `toDomain()`/`toEntity()` updated
  (round-tripped in `EntityMappersTest`, both a non-null and a null case).
- `data/local/AppDatabase.kt` — schema v19 -> v20, `MIGRATION_19_20` adds
  `categories.parentCategoryId INTEGER` via `ALTER TABLE`.

**UI**
- `ui/screens/budgets/CategoryBudgetsScreen.kt` — `CategoryBudgetsViewModel`
  now rolls up spend before computing statuses (`rollUpSpend` between
  `monthToDateByCategory` and `statuses`); `CategoryBudgetsUiState` carries
  the full category list so rows can resolve a parent's name and the edit
  dialog can offer a parent picker. `EditCategoryDialog` gained a
  dropdown (mirroring `BillsScreen`'s recurrence-picker pattern: a
  clickable `Card` + `DropdownMenu`) restricted to `candidateParents` —
  every category except itself and any category that already has it as a
  parent, which is what keeps a direct 2-cycle from being created through
  this screen. A row with a parent shows "Under {parent}" beneath its name.
- `ui/screens/goals/GoalsScreen.kt` — `GoalsUiState` gained
  `forecastByGoalId`; `GoalsViewModel`'s combine block computes
  `computeDashboardStats(...).avgMonthlyNet` and forecasts every visible
  goal from it. `GoalRow` shows "~N months to go at your current savings
  pace" (or "Target reached", or nothing when the pace can't project).
- `ui/screens/insights/InsightsScreen.kt` — `DateRangeFilterRow` gained a
  "1Y" chip for `Last12Months`, alongside the existing This month/3mo/6mo/
  YTD/All chips (additive — none of the existing presets changed or moved).

### Tests

- `CategoryBudgetCalculatorTest` — 4 new `rollUpSpend` cases: a parent's
  total includes a child's direct spend; a child's own total is unaffected
  by siblings or its parent; a childless top-level category just reports
  its own spend; a parent cycle terminates (asserts termination and
  non-negative results, not specific numbers -- a genuine cycle's exact
  split depends on iteration/memoization order, which is not a contract
  worth pinning down for a state the UI itself prevents).
- `GoalForecasterTest` — 7 cases: already-reached and past-target goals are
  both due now at zero months; an exact multiple of the average needs no
  rounding; a remainder rounds up (not truncates); zero and negative
  averages both refuse to project; a sub-one-month remaining gap still
  reports at least one month, never zero.
- `DashboardViewModelTest` (`DashboardDateRangeTest`) — `Last12Months`
  spans this month and the eleven before it; its label is "Last 12 months".
- `EntityMappersTest` — two new `Category` round-trip cases: a non-null
  `parentCategoryId`, and a null one for a top-level category.

### Deferred

- Category hierarchy is two levels only (no grandparents) — matches the
  spec's "parent/child" phrasing without building arbitrary-depth nesting
  nothing in the UI would exercise yet.
- No bulk "move all transactions from a deleted parent's children" tool;
  deleting a category (parent or child) already just un-categorizes its own
  transactions, unchanged from existing behavior.
- Splitwise-style group expense splitting (Group/Friend/TransactionSplit,
  split-allocation engine, debt simplification, P2P auto-settlement
  matching) and the bottom-nav restructure to Home/Plan & Budget/Splits &
  Groups/Activity/Settings are both still fully unbuilt — deliberately
  scoped out of this phase, not forgotten.

### CI verification

**Verified green on `677dd4d`.** Run `31816348033`:

- `Lint & Unit Tests` (job `94818852717`) — conclusion `success`. Read the
  "Run unit tests" step log directly rather than trusting the checkmark
  alone (this job has `continue-on-error: true`, per this file's standing
  rule):
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 3m 27s
  25 actionable tasks: 25 executed
  ```
  No `N tests completed, N failed` line — Gradle only prints that summary
  on failure, so its absence is the pass signal, including the new
  `CategoryBudgetCalculatorTest` `rollUpSpend` cases, `GoalForecasterTest`,
  the two new `EntityMappersTest` `Category` round-trip cases, and
  `DashboardViewModelTest`'s `Last12Months` cases.
- `Build APK (with retries)` (job `94819845915`) — conclusion `success`.
  Debug APK built on **attempt 1**, no retries needed — the real signal
  that the v19 -> v20 Room migration (`categories.parentCategoryId`)
  compiles and the schema validates cleanly, since a broken migration
  fails this build the same way a broken migration would fail on-device.
  `Verify build success` and `Check APK exists` both green; the
  non-gating Release APK build also succeeded.
- `Final Status` (job `94821469866`) — conclusion `success`.

## Phase 8 — Splitwise group-expense foundation

**Status:** landed, CI green after a fix
**Version:** `versionCode = 44`, `versionName = "0.31.0"`
**Migration:** v20 -> v21, five new tables (see below)

### Why

The second half of the original broad spec (Splitwise-style shared-expense
groups) had zero existing code anywhere in the tree — verified by survey
before starting, unlike the Monarch track which extended real existing
budgeting code. Built as its own phase rather than combined with Monarch,
reached as a sub-screen off Insights per the user's own scoping choice (no
bottom-nav restructure yet). Deliberately excludes the spec's "auto-settlement
ingestion" (cross-referencing incoming Zelle/Venmo/Wise P2P transfers against
outstanding IOUs): this ledger's entire ingestion layer is built around UAE
bank SMS/notification formats (ADIB, DEWA, Etisalat, AED), and Zelle/Venmo are
US-only apps with no presence in this codebase's parsing rules or SMS corpus
— building auto-match logic against transfer patterns that don't exist here
would be speculative, not a real feature. Manual "Settle up" (recording a
payment once it happens, however it happened) covers the actual need without
that dependency.

### What changed

**Domain**
- `domain/model/Models.kt` — `Friend` (id, name, createdAt, archived);
  `SplitType` enum (EQUAL/EXACT/PERCENTAGE/ADJUSTMENT/SETTLEMENT); `SplitGroup`
  (id, name, icon, `memberFriendIds: List<Long>`, createdAt, archived);
  `GroupExpense` (id, groupId, description, amount, currencyCode,
  `paidByFriendId: Long?`, splitType, createdAt); `ExpenseSplit` (id,
  expenseId, `friendId: Long?`, owedAmount). Null `paidByFriendId`/`friendId`
  means "me" throughout — the app's own user is never a `Friend` row, just an
  implicit participant identified by null everywhere a participant id appears.
- `domain/split/SplitAllocator.kt` (new) — pure, generic over the participant
  identifier (`<T>`, not hard-coded to `Long?`) so it's exercised with plain
  string ids under test. Four split types: `splitEqually` (rounds down to the
  cent, hands leftover cents to the first participants in order so the total
  is always exact, never a cent short or over); `splitByExactAmounts`
  (accepted only if the amounts already sum to the total); `splitByPercentages`
  (accepted only if percentages sum to 100 within a hundredth of a percent;
  every share but the last is rounded independently, the last absorbs
  whatever's left so rounding never drifts the total); `splitByAdjustments`
  (an equal split shifted by a per-participant +/-, accepted only if the
  adjustments net to zero). Each failure mode returns null rather than
  throwing — the caller is a live form, where "not valid yet" is routine.
- `domain/split/DebtSimplifier.kt` (new) — pure. `simplify(netBalances)`
  greedily pays the largest creditor from the largest debtor, repeatedly,
  until every balance is within a cent of zero — the standard practical
  approximation for debt simplification (true minimum-transaction-count is
  NP-hard) and what Splitwise itself uses. Each iteration is proven to fully
  zero out at least one side exactly (BigDecimal, no drift), so the loop
  always terminates within a bounded number of iterations.
- `domain/split/GroupBalances.kt` (new) — pure. `compute(expenses,
  splitsByExpenseId)` nets a group's expenses to one balance per participant:
  the payer is credited the full amount, every split is debited its own
  share — a payer who's also a participant nets to (amount paid - their own
  share), the everyday "I paid, I get back what everyone else owes" math.
  `SplitType.SETTLEMENT` needs no special case: it's just an expense whose
  one split is the full amount owed to the person being paid, so recording a
  settlement nets out through the exact same math as any other expense.

**Data**
- `data/local/entity/Entities.kt` — `FriendEntity`, `SplitGroupEntity`,
  `SplitGroupMemberEntity` (junction table, "me" is implicit and never a row),
  `GroupExpenseEntity`, `ExpenseSplitEntity`, all with `toDomain()`/`toEntity()`
  mappers (round-tripped in `EntityMappersTest`). `SplitGroupEntity.toDomain()`
  is the one exception to this codebase's no-arg mapper convention — it takes
  `memberFriendIds: List<Long>` as a parameter, since membership lives in the
  separate junction table and isn't a column on the group row itself.
- `data/local/Converters.kt` — `SplitType` <-> TEXT `@TypeConverter` pair,
  mirroring `BillRecurrence`'s.
- `data/local/dao/Daos.kt` — `FriendDao` (insert/update/observeActive/archive);
  `SplitGroupDao` (insert/update/observeActive/archive, plus
  clearMembers+insertMembers+observeAllMemberships for the junction table —
  a group's whole member list is replaced wholesale on every save rather than
  diffed, simpler for a handful of friends); `GroupExpenseDao`
  (insert/getById/observeAll/delete for expenses, insertSplits/
  getSplitsForExpense/observeAllSplits for their splits). `observeAll()`
  returns every group's expenses at once, not scoped per group — balances
  need the whole ledger, not one group's slice of it.
- `data/local/AppDatabase.kt` — schema v20 -> v21; `MIGRATION_20_21` creates
  `friends`, `split_groups`, `split_group_members` (composite PK, cascading FKs
  to both parents), `group_expenses` (cascading FK to its group), and
  `expense_splits` (cascading FK to its expense) — deleting a group or an
  expense cleans up everything under it automatically.
- `domain/repository/Repositories.kt` / `data/repository/SimpleRepositories.kt`
  — `FriendRepository`/`FriendRepositoryImpl`; `SplitGroupRepository`/
  `SplitGroupRepositoryImpl` (its `observeActive()` combines the group flow
  with the membership-junction flow to resolve each group's member id list;
  `save()` replaces a group's member rows wholesale; `addExpense()` inserts
  the expense then its splits in one call).
- `di/AppContainer.kt` — `friendRepository`/`splitGroupRepository` lazy vals.

**UI**
- `ui/screens/splits/SplitsScreen.kt` (new) — `SplitsViewModel` combines
  groups, friends, and every expense/split across all groups (4-flow
  `combine`, under Kotlin's 5-flow direct-overload limit) to compute "me"'s
  balance per group and the overall net. `SplitsScreen`: net owed/owing
  header, a card per group (icon, name, member count, that group's balance),
  tap to open `GroupDetailScreen`. `AddGroupDialog`: name/icon, a checklist
  of existing friends, plus an inline "add a new friend" field so a group can
  be created without a separate Friends-management screen first.
- `ui/screens/splits/GroupDetailScreen.kt` (new) — `GroupDetailViewModel`
  takes the group id via `SavedStateHandle` (mirrors `MerchantDetailViewModel`'s
  nav-arg pattern), computes this group's balances via `GroupBalances` and
  settle-up suggestions via `DebtSimplifier`. `GroupDetailScreen`: per-member
  balance list, a "Settle up" section listing each suggested payment with a
  one-tap "Record" action (calls `recordSettlement`, which builds the
  `SplitType.SETTLEMENT` expense described above), the expense list with
  delete, and an "Add expense" FAB. `AddExpenseDialog`: description, amount,
  paid-by picker, split-type picker, and a split-type-dependent input area —
  a checklist for EQUAL (who's actually splitting this one), a per-participant
  text field for EXACT/PERCENTAGE/ADJUSTMENT (entering nothing/zero for
  someone leaves them out of the split); the Add button stays disabled until
  `SplitAllocator` actually accepts the current input.
- `ui/AppViewModelProvider.kt` / `ui/MainActivity.kt` — `SplitsViewModel`/
  `GroupDetailViewModel` registered; `"splits"` and `"splits_group/{groupId}"`
  routes added.
- `ui/screens/insights/InsightsScreen.kt` — "Splits & groups" doorway row
  added next to Savings goals, matching every other secondary screen's
  reach-through-Insights pattern.

### Tests

- `SplitAllocatorTest` — 13 cases across all four split types: even and
  remainder-cent equal splits (and that the remainder is never dropped, only
  redistributed), a single-participant split, empty-participant rejection,
  exact amounts accepted/rejected by sum, percentages accepted/rejected by
  100% sum (including a near-100 tolerance case), the last percentage share
  absorbing rounding drift, adjustments net-to-zero accepted/rejected, and a
  participant missing from the adjustments map getting no adjustment.
- `DebtSimplifierTest` — 6 cases: already-settled balances need nothing;
  a simple two-person debt settles in one payment; three people settle in
  two payments (not the naive three); a chain of debts (a owes b, b owes c)
  collapses to one payment instead of two; a sub-cent balance is treated as
  already settled; a four-person case where every settlement's total exactly
  accounts for each debtor's debt.
- `GroupBalancesTest` — 6 cases: a payer who's also a participant nets to
  (paid - own share); a payer who took no share of their own expense is
  fully owed it back; multiple expenses accumulate into running balances;
  a `SETTLEMENT`-type expense nets exactly like any other expense (no special
  case needed); no expenses means no balances; an expense with no splits
  recorded yet still credits the payer alone.
- `EntityMappersTest` — 8 new round-trip cases across the five new
  domain/entity pairs, including the `SplitGroupEntity.toDomain(memberFriendIds)`
  extra-parameter case with both a populated and an empty member list, and
  both a `Friend`-payer and a null ("me") payer for `GroupExpense`/`ExpenseSplit`.

### Deferred

- P2P auto-settlement ingestion (Zelle/Venmo/Wise detection cross-referenced
  against outstanding IOUs) — see Why above; the ingestion layer has no
  P2P-transfer detection to hook into yet, in this or any prior phase.
- No dedicated Friends-management screen (rename/archive a friend outside of
  the inline "add a new friend" field in `AddGroupDialog`) — `archive()`
  exists on `FriendRepository` already, just not wired to any UI yet.
- No E2EE peer-to-peer/lightweight-cloud sync payload for groups — this
  phase is entirely local-first, matching every other feature in the app;
  the spec's sync schema was never scoped into this phase's ask.
- No bottom-nav restructure to the spec's 5-tab layout — per the user's own
  earlier scoping choice, still reached as a sub-screen off Insights.
- Settling up only offers the algorithm's own suggested payments; there's no
  free-form "record an arbitrary payment between two people" action yet.

### CI verification

**First push (`28227cb`) had a real compile failure.** Run `31819990643`,
job `94830659215` (`Lint & Unit Tests`) reported conclusion `success` --
`continue-on-error: true` masking the real result, per this file's standing
rule -- but the log itself showed:

```
e: .../app/src/test/java/com/xemmy/mmbank/domain/split/SplitAllocatorTest.kt:59:35
   Cannot infer type for this parameter. Please specify it explicitly.
e: .../SplitAllocatorTest.kt:59:35 Not enough information to infer type argument for 'T'.
e: .../SplitAllocatorTest.kt:59:76 Cannot infer type for this parameter. Please specify it explicitly.
e: .../SplitAllocatorTest.kt:59:76 Not enough information to infer type argument for 'K'.
> Task :app:compileDebugUnitTestKotlin FAILED
BUILD FAILED in 2m 47s
```

Line 59 called `SplitAllocator.splitByExactAmounts(BigDecimal("50.00"),
emptyMap())` -- `splitByExactAmounts<T>` needs `T` inferred from its `Map<T,
BigDecimal>` argument, and a bare `emptyMap()` supplies no type information
of its own (`emptyMap<K, V>()` needs its own `K`/`V` inferred too), so
neither side had anything to anchor to. Every other `emptyList()`/`emptyMap()`
call in the new test files either called a non-generic function (target-typed
from a concrete parameter type, which infers fine) or already carried an
explicit type argument (`emptyList<String>()`) -- this was the one bare case.
Fixed by writing `emptyMap<String, BigDecimal>()` explicitly, matching the
String-id convention the rest of `SplitAllocatorTest` already uses. `Build
APK` (job `94831439595`) had already succeeded on attempt 1 in this same run
-- the debug-source compile these tests didn't block was already known-good
-- so this was purely a unit-test compile issue. Fixed and pushed as `452f677`.

**Verified green on `452f677`.** Run `31820989966`:

- `Lint & Unit Tests` (job `94833831346`) — conclusion `success`. Read the
  "Run unit tests" step log directly:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 49s
  25 actionable tasks: 10 executed, 15 from cache
  ```
  No `N tests completed, N failed` line — per this file's established
  convention, Gradle only prints that summary when something fails, so its
  absence is the pass signal, including the fixed `SplitAllocatorTest`, the
  new `DebtSimplifierTest` and `GroupBalancesTest`, and the 8 new
  `EntityMappersTest` round-trip cases for the five new domain/entity pairs.
- `Build APK (with retries)` (job `94834086203`) — conclusion `success`.
  Debug APK built on **attempt 1** in both this run and the prior one, no
  retries needed either time — the real evidence the v20 -> v21 migration
  (five new tables, cascading foreign keys across `split_group_members`,
  `group_expenses`, `expense_splits`) compiles and validates cleanly.
  `Verify build success` and `Check APK exists` both green; the non-gating
  Release APK build also succeeded.
- `Final Status` (job `94835698309`) — conclusion `success`.
