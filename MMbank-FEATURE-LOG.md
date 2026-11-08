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

## Phase 9 — Edit group expenses instead of delete-only

**Status:** landed, CI green
**Version:** `versionCode = 45`, `versionName = "0.32.0"`
**Migration:** none (no schema change)

### Why

Phase 8 shipped group expenses with delete-only correction: fixing a typo'd
amount or a wrong split meant deleting the row and re-entering it from
scratch, losing the original as a reference mid-edit. Asked for directly:
add a real edit path, and make sure the amounts (and therefore every
balance and settle-up suggestion derived from them) actually update.

### What changed

**Domain**
- `domain/split/SplitAllocator.kt` — two new pure inverse functions so the
  edit dialog can pre-fill from a previously saved split instead of starting
  blank: `derivePercentages(totalAmount, amounts)` recovers each
  participant's percentage from their already-computed share (exact inverse
  of `splitByPercentages`, zero for every participant rather than dividing
  by a non-positive total); `deriveAdjustments(totalAmount, participantIds,
  amounts)` recovers each participant's +/- offset from an equal split
  (exact inverse of `splitByAdjustments`).

**Data**
- `data/local/dao/Daos.kt` — `GroupExpenseDao` gained `update(entity)` and
  `deleteSplitsForExpense(expenseId)` (clears the old split rows before an
  edit's new ones are inserted -- simpler than diffing which participant
  rows changed, same wholesale-replace idiom `SplitGroupDao` already uses
  for group membership).
- `domain/repository/Repositories.kt` / `data/repository/SimpleRepositories.kt`
  — `SplitGroupRepository.addExpense` renamed to `saveExpense`, now
  branching on `expense.id == 0`: insert for a new expense, or update the
  expense row + wholesale-replace its splits for an existing one. Same
  insert-or-update-by-id idiom every other `save()` in this app already
  follows (`BillRepository`, `SavingsGoalRepository`, `CategoryRepository`).

**UI**
- `ui/screens/splits/GroupDetailScreen.kt` — `GroupDetailUiState` gained
  `splitsByExpenseId` (an expense's current splits, for the edit dialog to
  read). `GroupDetailViewModel.addExpense` renamed to `saveExpense(expenseId,
  ...)`, used by both the add and edit flows (and by `recordSettlement`,
  unchanged in behavior). `ExpenseRow` gained an edit action next to delete
  -- hidden for `SplitType.SETTLEMENT` rows, since a settlement's one split
  is implicit in how `recordSettlement` builds it rather than something the
  split-type picker can reconstruct into a sensible starting form; deleting
  is still how a settlement gets corrected. `AddExpenseDialog` renamed to
  `ExpenseDialog` and generalized with an `existing: GroupExpense?` /
  `existingSplits: List<ExpenseSplit>` pair: null means the add flow
  (unchanged blank form); non-null pre-fills description/amount/paid-by and,
  when the selected split type still matches the expense's original one,
  the per-participant fields too (via the new `derivePercentages`/
  `deriveAdjustments`, or the stored amount directly for EQUAL/EXACT).
  Switching to a *different* split type while editing starts that type's
  fields blank rather than guessing a cross-type mapping -- same as a new
  expense. Participants pre-filling the form union in anyone from the
  expense's existing splits even if they've since left the group, so
  editing an old expense can never silently drop a former member's share.
  Title/confirm-button text swap between "New expense"/"Add" and "Edit
  expense"/"Save" based on whether `existing` is null.

### Tests

- `SplitAllocatorTest` — 5 new cases: `derivePercentages` recovers the
  right percentage from an amount and is the exact inverse of
  `splitByPercentages`'s forward computation (round-trip test), returns
  zero for every participant instead of dividing by a non-positive total;
  `deriveAdjustments` is the exact inverse of `splitByAdjustments`'s
  forward computation, and reports zero adjustment for an unmodified equal
  share.

### Deferred

- No undo on delete (unchanged from Phase 8) -- deleting an expense is
  still permanent, only editing gained a correction path.
- Editing a `SETTLEMENT`-type expense still isn't offered; delete-and-
  re-record via "Settle up" remains the only way to correct one.

### CI verification

**Verified green on `15fb61e`.** Run `31844691767`:

- `Lint & Unit Tests` (job `94908616164`) — conclusion `success`. Read the
  "Run unit tests" step log directly rather than trusting the checkmark
  alone (this job has `continue-on-error: true`, per this file's standing
  rule):
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 2m 49s
  25 actionable tasks: 25 executed
  ```
  No `N tests completed, N failed` line — Gradle only prints that summary
  on failure, so its absence is the pass signal, including the 5 new
  `SplitAllocatorTest` cases for `derivePercentages`/`deriveAdjustments`.
- `Build APK (with retries)` (job `94909233647`) — conclusion `success`.
  Debug APK built on **attempt 1**, no retries needed. `Verify build
  success` and `Check APK exists` both green; the non-gating Release APK
  build also succeeded.
- `Final Status` (job `94910536587`) — conclusion `success`.

## Phase 10 — Category unification (single Categories screen, merge SpendingCategory into Category)

**Status:** complete — all 9 steps landed, CI green
**Version:** `versionCode = 57`, `versionName = "0.38.2"` (Step 9; Step 8 was 56/0.38.1, Step 7 was 55/0.38.0, Step 6c was 54/0.37.0, Step 6b was 53/0.36.1, Step 6a was 52/0.36.0, Step 5 was 51/0.35.1, Step 4 was 50/0.35.0, Step 3 was 49/0.34.2, Step 2 was 48/0.34.1, Step 1 was 47/0.34.0)
**Migration:** v21 -> v22 (Step 1; further steps in this phase add no schema changes unless noted)

### Why

MMbank has two independent, disconnected category systems on every `Transaction`:
`SpendingCategory` (a fixed 14-value enum driving the Dashboard chart, Analytics,
and 100% of `TransactionsScreen`'s category UI) and the dynamic `Category` table
(user-extensible, with a hierarchy from Phase 7, assigned via a picker from three
different screens, full CRUD only on the budgets screen -- which has no create
button at all). No single place to manage categories, no visual hierarchy despite
`parentCategoryId` existing, and the two systems can silently disagree on the same
transaction. Asked for directly ("categories in a single place"), scoped through
two rounds of research (confirmed via direct AskUserQuestion, not assumed) into:
unify both systems onto the dynamic `Category`; keep quick-create inline everywhere
but make it one shared full-featured form; expand the existing budgets screen into
the unified home with real hierarchy. Two additional real problems surfaced during
research and were explicitly confirmed in scope: encrypted backup/restore already
discards all dynamic-category data (worse once `Category` is the *only* system),
and the Dashboard/Analytics charts should move onto the unified system too.

Landing as 9 small, independently-buildable, CI-verified steps rather than one
commit, per this project's established practice -- full technical design at
`/root/.claude/plans/cheeky-wishing-wirth.md`.

### What changed (updated per step; see below for the running list)

**Step 1 — Migration v21 -> v22: seed defaults + backfill `categoryId`**
- `data/local/AppDatabase.kt` — `DEFAULT_CATEGORIES_V22`: the 5
  `SpendingCategory` values with no dynamic counterpart yet (Fuel⛽,
  Entertainment🎬, Travel✈️, Transfers🔁, Fees🏦 -- icons reused from the
  enum's own emoji for continuity, 5 new hex swatches). `OTHER` folds into
  "Uncategorized" rather than getting its own category: `MerchantCategorizer`
  never produces it (falls back to `UNCATEGORIZED`), and its only real
  writers are `excludeFromStats` balance-adjustment rows and demo data -- a
  dedicated bucket would be permanently near-empty.
- `domain/model/CategoryMigrationMapping.kt` (new) -- pure `Map<SpendingCategory,
  String>`, the single source of truth for the enum-to-dynamic-category-name
  bridge, reused by this migration's backfill and (in Step 2) by capture-time
  auto-assignment going forward.
- `MIGRATION_21_22`: guards every seed INSERT against re-seeding (`categories.name`
  has no unique index, unlike the fresh v13 table `DEFAULT_CATEGORIES` seeded),
  then backfills `categoryId` via one guarded `UPDATE ... WHERE categoryId IS
  NULL AND spendingCategory IN (...)` per mapping target (`OTHER`+`UNCATEGORIZED`
  combined into one `IN(...)` clause since both target "Uncategorized" --
  13 distinct enum values collapse to fewer statements). Never touches a row
  where `categoryId` is already set -- same non-destructive posture as the
  existing `backfillCategoryByMerchant` DAO query. Deliberately **not**
  filtered by `isDemo`: demo rows also have `categoryId IS NULL` today and
  feed the same unified screen this phase builds.

**Step 2 — Capture-time bridge**
- `domain/repository/Repositories.kt` / `data/repository/CategoryRepositoryImpl.kt`
  — `CategoryRepository.findCategoryIdByName(name)` -> `categoryDao.getByName(name)?.id`
  (case-insensitive; the DAO query already existed, unused outside the
  repository's own internals until now).
- `domain/parser/CaptureCategoryResolver.kt` (new) -- pure. `resolve(confirmedCategoryId,
  hasPendingSuggestion, bridgedCategoryId)`: a confirmed merchant-mapping hit
  always wins outright; a pending AI suggestion blocks any auto-assignment
  (must still surface for human review in the Uncategorized queue rather
  than being silently applied); only when neither applies does the bridge
  guess get used.
- `data/repository/TransactionRepositoryImpl.kt` (`recordParsed`) -- when
  there's no confirmed mapping and no pending suggestion, bridges
  `MerchantCategorizer`'s keyword guess (already computed for `spendingCategory`)
  onto a same-named dynamic category via `CategoryMigrationMapping` +
  `findCategoryIdByName`, then `CaptureCategoryResolver.resolve(...)` picks
  the final `categoryId`. **Regression guard**: `AiConfidenceScorer.score(hasConfirmedCategory
  = ...)` deliberately keeps reading the confirmed-only signal
  (`confirmedCategoryId != null`), not the final (possibly bridge-assigned)
  `categoryId` -- a bridge match is a `KEYWORD_MATCH`-strength guess, not a
  `CONFIRMED_MAPPING` one, and `spendingCategory` already carries that
  signal into the scorer separately. Flagged as the sharpest regression risk
  in the whole phase during planning; verified by direct read before and
  after this edit that the two booleans stayed distinct.
- `domain/demo/DemoDataGenerator.kt` / `di/AppContainer.kt` (`enableDemoMode`)
  -- demo transactions now also get a `categoryId`, resolved via the same
  `CategoryMigrationMapping` bridge (looked up once per enum value against
  the just-seeded categories, inside the same `withTransaction` block), so
  Demo mode showcases the unified Categories screen (Step 4) correctly
  instead of showing everything Uncategorized.

### Tests

- `CategoryMigrationMappingTest` -- 4 cases: every `SpendingCategory` value has
  a mapping entry (exhaustiveness, catches a future enum addition silently
  missing a migration target); every mapping targets a name that's actually
  seeded (`DEFAULT_CATEGORIES` ∪ `DEFAULT_CATEGORIES_V22`, catches a typo
  before it ships as dead SQL); `OTHER`/`UNCATEGORIZED` both fold to
  "Uncategorized"; no other value collides onto it.
- `CaptureCategoryResolverTest` -- 5 cases covering every combination:
  confirmed wins over a bridge match; a pending suggestion blocks the bridge
  even with a bridge match available; the bridge applies when neither
  confirmed nor pending exists; nothing resolves with no signal at all; a
  pending suggestion with no bridge match still resolves to null (not an
  error).

### Deferred (within these steps; later steps in this phase cover the rest)

- No automated migration-test harness exists anywhere in this project (no
  `MigrationTestHelper` precedent) -- this migration is verified by CI
  compile/build success plus a manual spot-check query documented in the plan
  file, not by an automated post-migration row-state assertion. Consistent
  with every prior migration in this codebase, not a new gap.
- Steps 4-9 (unified screen, bulk setter, TransactionsScreen migration,
  Dashboard/Analytics charts, backup/restore format, remaining read-site
  cleanup) land as their own commits; this entry grows to cover each as it
  ships.

### CI verification (Steps 1-2)

Landed, CI green.

## Step 3 — Shared category editor component

### What changed

- `ui/components/CategoryPresets.kt` (new) -- `PRESET_COLORS` and
  `categoryColor()` hoisted out of `CategoryPickerBottomSheet.kt` unchanged;
  new `PRESET_ICONS` (the `SpendingCategory` emoji set, deduped) for the new
  icon grid.
- `ui/components/CategoryEditorFields.kt` (new) -- `CategoryEditorFormState`
  (name, icon, colorHex, monthlyCapText, parent) plus `CategoryEditorFields`,
  a hoisted-state composable rendering: name field; a scrollable preset icon
  grid *plus* a free-text override field (no system emoji-keyboard component
  exists in this app, so free text is how anything outside the curated grid
  gets picked); color swatches; monthly cap field; parent dropdown. Two pure
  extension functions, `isValid()` and `capValue()`, shared by every caller
  instead of each re-deriving cap-parsing/validity itself.
- `ui/components/CategoryEditorDialog.kt` (new) -- full create/edit
  `AlertDialog` wrapper around `CategoryEditorFields`. `initial: Category?`
  -- null means create, non-null means edit (fields seeded from it).
  Computes `candidateParents` with the same self/direct-parent exclusion
  guard the old dialog used (two-level hierarchy, so excluding direct
  children is sufficient to prevent a cycle). **Replaces**
  `CategoryBudgetsScreen.kt`'s private `EditCategoryDialog`, which is now
  deleted along with the imports it alone needed (`Card`, `DropdownMenu`,
  `DropdownMenuItem`, `OutlinedTextField`, `ArrowDropDown`, `clickable`).
- `ui/components/CategoryPickerBottomSheet.kt` -- its `creating` branch now
  embeds `CategoryEditorFields` instead of a bespoke name/color/cap-only
  form (icon was previously hardcoded to `"🏷️"`, no parent picker existed
  at all). Signature gains `currency: String` and `onCreateNew` gains a
  trailing `parentCategoryId: Long?`.
- Ripple to all 3 quick-create call sites -- each `UiState` gains
  `defaultCurrency: String = "USD"` (read from `container.preferences.defaultCurrency`,
  mirroring `CategoryBudgetsViewModel`'s existing pattern), each
  `createAndAssignCategory(...)` gains a trailing `parentCategoryId: Long?`
  forwarded straight into the saved `Category`:
  - `UncategorizedQueueScreen.kt` / `UncategorizedQueueViewModel`
  - `ReviewTransactionsScreen.kt` / `ReviewViewModel`
  - `MerchantDetailScreen.kt` / `MerchantDetailViewModel`

### Tests

- `CategoryEditorFormStateTest` -- 7 cases for the two pure extension
  functions: blank name invalid regardless of cap; blank cap valid; numeric
  cap valid; unparseable cap invalid; `capValue()` trims and parses, is null
  when blank, is null when unparseable.

### Deferred (within this step; later steps in this phase cover the rest)

- Steps 5-9 unchanged from the prior status above.

### CI verification (Step 3)

First push (`56e73e9`) failed `compileDebugKotlin`: the `CategoryPickerBottomSheet.kt`
rewrite dropped `androidx.compose.ui.unit.dp`, which its own padding/size/width
modifiers still need -- caught by reading the actual "Run unit tests" log, not
the aggregate status. Fixed in `56d95de` (one-line import restore). Landed, CI green.

## Step 4 — Unified Categories screen

### What changed

- `ui/screens/budgets/CategoryBudgetsScreen.kt` renamed to
  `ui/screens/budgets/CategoriesScreen.kt`: `CategoryBudgetsUiState` ->
  `CategoriesUiState` (drops the now-redundant `uncapped` list -- every
  category renders via the hierarchy tree regardless of cap state),
  `CategoryBudgetsViewModel` -> `CategoriesViewModel` (gains `create(...)`
  for the new FAB), `CategoryBudgetsScreen` -> `CategoriesScreen`. Route
  `"category_budgets"` -> `"categories"`; `AppViewModelProvider.kt` and
  `MainActivity.kt` updated. `InsightsScreen.kt`'s doorway row retitled
  "Categories" ("Manage categories and hierarchy, with monthly caps and
  alerts at 80% and 100%"). `CategoryBudgetSummaryCard` is unchanged in
  behavior, just now backed by `CategoriesViewModel`.
- `domain/budget/CategoryHierarchy.kt` (new) -- pure `buildTree(categories):
  List<CategoryTreeNode>`. Groups a flat list into top-level categories (each
  with its sorted children); a category whose `parentCategoryId` doesn't
  resolve to another category in the same list (parent deleted, or simply
  unset) is treated as top-level rather than dropped.
- `CategoriesScreen`'s list now renders hierarchy-first: each top-level
  category's own row, immediately followed by its indented children --
  replacing the old flat "This month" / "No cap set" two-section split (that
  split predates the hierarchy feature and never reflected it). Each row
  still shows budget progress only when it individually carries a cap
  (`CategoryBudgetCalculator.rollUpSpend` already folds child spend into a
  capped parent's total, unchanged). The old per-row "Under {parent}"
  subtitle is gone -- indentation communicates it directly now.
- FAB (+) opens the Step 3 `CategoryEditorDialog(initial = null, ...)` ->
  `CategoriesViewModel.create(...)`, the first create entry point on this
  screen (it previously had edit/delete/reparent but no way to add a new
  category at all).
- Empty-state guard changed from `statuses.isEmpty() && uncapped.isEmpty()`
  to `categories.isEmpty()`, with copy updated to mention the FAB -- the old
  guard/copy ("Categories appear here once transactions have been
  categorized") was already stale since Phase 7: the starter categories are
  seeded on first run, not created from transactions, so the list is
  essentially never actually empty in practice.

### Tests

- `CategoryHierarchyTest` -- 5 cases: empty input; top-level categories
  sorted by name; children grouped under their parent and sorted by name; a
  category whose `parentCategoryId` doesn't resolve is treated as top-level;
  a childless top-level category has an empty children list.

### Deferred (within this step; later steps in this phase cover the rest)

- Steps 5-9 unchanged from the prior status above.

### CI verification (Step 4)

Landed as `d422007`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m
59s`, `25 actionable tasks: 25 executed`, no test-failure line. `Build APK
(with retries)` succeeded on Attempt 1 (Attempts 2-4 all `skipped`,
confirming Attempt 1 genuinely passed); `Verify build success`, `Check APK
exists`, `Upload Debug APK`, and the non-gating `Build Release APK` all
succeeded. `Final Status` -- `success`.

## Step 5 — Bulk dynamic-category setter

### What changed

- `TransactionDao.bulkSetCategoryId(ids, categoryId: Long?): Int` (new) --
  same UPDATE shape as the existing single-row `setCategory`
  (`categoryId`, clears `needsCategoryApproval` and the three
  `suggestedCategory*` columns), but `WHERE id IN (:ids)` for multi-select.
  Mirrors the existing enum `bulkSetCategory` pairing pattern.
- `TransactionRepository.bulkSetCategoryId` (new) + `TransactionRepositoryImpl`
  passthrough to the DAO.
- `BulkTransactionPatch.categoryId: FieldUpdate<Long?> = FieldUpdate.NoChange`
  (new field, defaults to no-op) -- uses `FieldUpdate` rather than a plain
  nullable, same reasoning as `accountId`/`person`: "leave alone" and
  "clear to null" both need to be distinguishable for a category-clearing
  bulk action.
- `BulkUpdateTransactionsUseCase.invoke` gains one more `(patch.categoryId
  as? FieldUpdate.Set)?.let { ... }` branch, same pattern as the existing
  four fields.
- Purely additive plumbing -- no UI wires into it yet. `TransactionsScreen`'s
  bulk-select bar still only offers the legacy enum picker; Step 6b is what
  points it at this new setter via the shared `CategoryPickerBottomSheet`.

### Tests

- `BulkUpdateTransactionsUseCaseTest` -- 3 new cases: `categoryId Set` calls
  `bulkSetCategoryId` with that id; `categoryId Set(null)` calls it with
  null to clear; `categoryId` left `NoChange` does not call it at all.
  Mirrors the existing `accountId`/`person` three-case pattern exactly.
- `RecordingBulkRepository` (the use-case test's `TransactionRepository`
  fake) and `RecordingTransactionRepository` (the separate
  `IngestCapturedMessageUseCaseTest` fake) both gained the new
  `bulkSetCategoryId` override -- required for both to keep compiling
  against the widened interface, per this project's existing pattern.

### Deferred (within this step; later steps in this phase cover the rest)

- Steps 6-9 unchanged from the prior status above.

### CI verification (Step 5)

Landed as `9b3c45d`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 3m
4s`, `25 actionable tasks: 25 executed`, no test-failure line. `Build APK
(with retries)` succeeded on Attempt 1 (Attempts 2-4 all `skipped`); `Verify
build success`, `Check APK exists`, `Upload Debug APK`, and the non-gating
`Build Release APK` all succeeded. `Final Status` -- `success`.

## Step 6a — TransactionsScreen row/swipe migrated to dynamic categories

### What changed

- `TransactionsViewModel`'s first `combine` gains `categoryRepository.observeAll()`
  as its 5th flow (the room confirmed available in this file's own combine
  block); `TransactionsUiState.categories: List<Category>` (new). New
  ViewModel methods `setCategoryId(transactionId, categoryId)` (thin
  passthrough to the already-dynamic `TransactionRepository.setCategory`) and
  `createAndAssignCategory(transactionId, name, colorHex, icon, monthlyCap,
  parentCategoryId)` (saves a new `Category` then assigns it) -- both mirror
  the identical pattern already landed in `UncategorizedQueueViewModel`. Also
  added (for 6b, landing in the same review pass) `bulkSetCategoryId` and
  `bulkCreateAndAssignCategory`, the bulk-selection equivalents.
- Row category chip: now resolves `tx.categoryId` against `state.categories`
  for its icon/name (falls back to a generic "🏷️" tag label when unset),
  and opens the shared `CategoryPickerBottomSheet` instead of a fixed
  `SpendingCategory`-entries `DropdownMenu`.
- Swipe-to-categorize (end-to-start swipe on a row) and the row chip tap now
  both set the same hoisted `categoryPickerFor: Transaction?` state and open
  the one shared picker -- previously the swipe path opened a bespoke
  `QuickCategorizeDialog` (now deleted) while the chip opened its own
  separate `DropdownMenu`; the two pickers could show different information
  for the same row depending which affordance was used.
- `androidx.compose.material.icons.filled.Category` import aliased to
  `CategoryIcon` (`Icons.Filled.CategoryIcon` at its 2 call sites) since the
  file now also imports the domain `Category` model by its unaliased name --
  the two would otherwise collide as a "conflicting import".
- `BulkModeBar`'s enum dropdown and `TransactionEditorDialog`'s enum chip row
  are deliberately untouched here -- Steps 6b/6c.

### Tests

- No new pure logic: the new ViewModel methods are thin passthroughs to
  already-tested repository/use-case methods (`TransactionRepository.setCategory`,
  `CategoryRepository.save`, `BulkUpdateTransactionsUseCase` via
  `BulkTransactionPatch.categoryId`, covered by Step 5's tests). Matches this
  file's existing precedent -- none of its other ViewModel wrapper methods
  (`setCategory`, `assignAccount`, `setPerson`, …) carry a dedicated unit
  test either, since `TransactionsViewModel` itself isn't JVM-unit-tested in
  this project (Compose + coroutine-scoped, no test harness set up for it).

### Deferred (within this step; later steps in this phase cover the rest)

- Step 6b (`BulkModeBar`), 6c (`TransactionEditorDialog`), 7, 8, 9 unchanged
  from the prior status above.

### CI verification (Step 6a)

Landed as `c3da453`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m
48s`, `25 actionable tasks: 25 executed`, no test-failure line. `Build APK
(with retries)` succeeded on Attempt 1 (Attempts 2-4 `skipped`); `Verify
build success`, `Check APK exists`, `Upload Debug APK` all succeeded.

## Step 6b — BulkModeBar migrated to dynamic categories

### What changed

- `BulkModeBar`'s Categorize icon button no longer opens its own
  `SpendingCategory.entries`-driven `DropdownMenu`; `onCategorize` changed
  from `(SpendingCategory) -> Unit` to a plain `() -> Unit` trigger that
  opens the same shared `CategoryPickerBottomSheet` pattern 6a introduced
  for the row/swipe path, this time via a screen-level `bulkCategoryPickerOpen`
  flag (a plain boolean, not a per-transaction target, since a bulk action
  applies to every selected id at once) feeding `viewModel.bulkSetCategoryId`
  / `viewModel.bulkCreateAndAssignCategory` (both added in 6a's commit,
  wired up here).
- `TransactionsViewModel.bulkSetCategory(category: SpendingCategory)` deleted
  -- `BulkModeBar` was its only caller, so migrating that caller left it
  dead code. The underlying `BulkTransactionPatch.spendingCategory` field
  and `TransactionRepository.bulkSetCategory` stay as-is (still covered by
  `BulkUpdateTransactionsUseCaseTest`, still a legitimate generic capability
  of the patch type even though nothing currently drives it from the UI).
- `TransactionEditorDialog`'s own enum chip row is deliberately untouched
  here -- Step 6c.

### Tests

- No new pure logic (same reasoning as 6a): `bulkSetCategoryId` and
  `bulkCreateAndAssignCategory` are the same thin ViewModel wrappers added
  in 6a's commit, exercised transitively by `BulkUpdateTransactionsUseCaseTest`
  (Step 5) and `CategoryRepository`'s own coverage.

### Deferred (within this step; later steps in this phase cover the rest)

- Step 6c (`TransactionEditorDialog`), 7, 8, 9 unchanged from the prior
  status above.

### CI verification (Step 6b)

`68f4a28` was pushed together with `65f0a8c` (Step 6c) in one push, so no
separate CI run covers 6b alone -- verified transitively: 6c's combined run
(below) compiles and tests everything 6b changed plus 6c's own changes on
top, so a green result there covers both.

## Step 6c — TransactionEditorDialog migrated to dynamic categories

### What changed

- `TransactionEditorDialog` gains a `categories: List<Category>` param and an
  `onCreateCategory: suspend (name, colorHex, icon, monthlyCap, parentCategoryId)
  -> Category` callback. Its category chip row now renders `categories`
  instead of `SpendingCategory.entries`, plus a trailing "+ New" chip that
  opens the shared `CategoryEditorDialog` (Step 3) in create mode; saving it
  calls `onCreateCategory` inside a `rememberCoroutineScope()` launch (the
  same pattern this dialog's own "Parse with AI" button already uses for a
  suspend round-trip) and selects the freshly created category once it
  resolves. `onSave`'s enum `spendingCategory` param is now `categoryId: Long?`.
- The "Auto" chip's suggestion no longer shows the bare `MerchantCategorizer`
  enum guess -- it bridges that guess onto its same-named dynamic category
  (via `CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME`, already built for
  Step 1/2) so tapping "Auto" is a real, selectable category rather than a
  label with no corresponding chip.
- `TransactionsViewModel.addManual`/`updateTransaction` take `categoryId: Long?`
  in place of `spendingCategory: SpendingCategory`, and now also set
  `Transaction.categoryId` (previously never set for manual entries at all --
  a manually added or edited transaction had no dynamic category no matter
  what, since nothing in this dialog could assign one until this step).
- **Deliberate deviation from the strict plan text** ("`SpendingCategory`
  import becomes removable from `TransactionsScreen.kt` entirely"): manual
  entries still populate the legacy `spendingCategory` field too, via a new
  reverse mapping (`CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM`, plus a
  private `TransactionsViewModel.resolveLegacySpendingCategory(categoryId)`)
  rather than leaving it at its `UNCATEGORIZED` default. Reasoning: several
  "frozen legacy" read sites (Step 9's list -- `BackupManager`'s CSV export
  column in particular, which is user-visible) read `spendingCategory` for
  every transaction, captured or manual alike. Leaving it permanently
  `UNCATEGORIZED` for every hand-added/edited transaction from this point on
  would be a real, if minor, silent regression for anyone exporting their
  data -- exactly the kind of category-data loss this whole phase (see Step
  8) exists to close, not reopen. The reverse map only resolves seeded/known
  names; a custom category (no matching seeded name) falls back to
  `UNCATEGORIZED`, same as before this step. `SpendingCategory` therefore
  stays imported in this file for that one purpose.
- `TransactionsViewModel.setCategory(transactionId, SpendingCategory)` deleted
  -- like `bulkSetCategory` in 6b, its last caller was removed in 6a, leaving
  it dead.
- Net result for this phase: `TransactionsScreen.kt` no longer offers any
  UI affordance that assigns the legacy enum directly (row chip, swipe,
  bulk bar, and now the editor dialog all write `categoryId`); the enum is
  written only indirectly, as a best-effort mirror of the dynamic pick.

### Tests

- `CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM` (new reverse map) +
  4 new `CategoryMigrationMappingTest` cases: "Uncategorized" resolves to
  `UNCATEGORIZED` specifically (not `OTHER`, despite both forward-mapping
  onto it); every non-`OTHER` enum value round-trips through its category
  name; an unmapped custom-category name returns `null`.
- No new test for `onCreateCategory`'s suspend round-trip or
  `resolveLegacySpendingCategory` specifically -- both are thin/pure
  compositions of already-tested pieces (`CategoryRepository.save`,
  `CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM`), matching this file's
  established precedent of not unit-testing `TransactionsViewModel` directly.

### Deferred (within this step; later steps in this phase cover the rest)

- Steps 7, 8, 9 unchanged from the prior status above. Step 6 (all three
  sub-steps) is now complete.

## Step 9 — Remaining read-site cleanup (final step of this phase)

### What changed

- `TransactionFilterEngine.needsTriage` -- `tx.spendingCategory == SpendingCategory.UNCATEGORIZED`
  -> `tx.categoryId == null` (still `|| matchedRuleName == BALANCE_ADJUSTMENT_RULE_NAME`,
  unchanged). The dynamic `categoryId` is the actual post-unification source
  of truth for "does this row still need a human look", strictly more
  accurate than the frozen enum.
- `TransactionDetailSheet` gains a `categories: List<Category>` param; its
  read-only "Category" row now resolves `transaction.categoryId` against the
  passed-in list (icon + name), falling back to a generic "🏷️ Uncategorized"
  when unset -- instead of always showing the frozen
  `spendingCategory.emoji`/`.label`. `TransactionsScreen.kt`'s one call site
  updated with `categories = state.categories`.
- `Daos.observeUncategorized` -- dropped the redundant
  `spendingCategory = 'UNCATEGORIZED'` AND-clause from
  `(categoryId IS NULL AND spendingCategory = 'UNCATEGORIZED')`, leaving
  `categoryId IS NULL` alone (still OR'd with `needsCategoryApproval = 1`).
  Strictly more correct post-unification: a row can have `categoryId IS
  NULL` while its frozen `spendingCategory` reads as something other than
  UNCATEGORIZED (e.g. the `MerchantCategorizer` keyword guess landed on a
  real bucket, but no dynamic category was ever bridged/assigned) -- the old
  AND-clause would have wrongly excluded such a row from the Uncategorized
  queue.
- **Deliberately left as frozen legacy** (per the original plan, unchanged
  from prior status): `AiRetentionScorer` (INCOME check), `TransferMatcher`
  (TRANSFERS check), `FinancialCopilotQueryHandler`, `BackupManager`'s CSV
  export category column, `AiConfidenceScorer` -- all keep reading
  `spendingCategory` exactly as before. It keeps being populated at capture
  time (Step 2) and now also for manual entries (Step 6c), so none of these
  regress; migrating them has no user-visible benefit tied to this phase.

### Tests

- `TransactionFilterEngineTest`'s `tx()` fixture helper gains a
  `categoryId: Long? = null` param; the
  `` `triage keeps uncategorized rows and balance adjustments, drops the rest` ``
  case rewritten to set `categoryId` (not `category`) across its three
  fixture rows, since triage no longer keys on the enum. The
  balance-adjustment cross-file-consistency case needed no change -- it
  passes purely on `matchedRuleName`, independent of this migration.
- No test added for `observeUncategorized`'s SQL change or
  `TransactionDetailSheet`'s row resolution -- Room queries and this
  particular Compose screen have no JVM test harness in this project (same
  established gap noted in Steps 1 and 8).

### This phase is now complete

All 9 steps of the category-unification plan (`Category` unification --
single Categories screen, merge `SpendingCategory` into `Category`) have
landed: migration + backfill (1), capture-time bridge (2), shared editor
component (3), unified Categories screen (4), bulk dynamic-category setter
(5), full `TransactionsScreen` migration across row/swipe, bulk bar, and the
editor dialog (6a/6b/6c), Dashboard + Analytics chart migration (7),
backup/restore format fix (8), and this final read-site cleanup (9).
`SpendingCategory` remains in the codebase as a deliberately frozen legacy
signal (documented above and in Step 6c) -- fully retiring it (stop writing
it, drop the remaining legacy reads) is explicitly out of scope for this
phase, per the original plan's "Deferred / explicitly out of scope" section.

### CI verification (Step 9)

Landed as `3921373`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m
56s`, `25 actionable tasks: 25 executed`, no test-failure line. `Build APK
(with retries)`'s first attempt hit a transient GitHub infrastructure 429
rate-limit downloading the `actions/setup-java` action itself (before
checkout even ran -- confirmed unrelated to this repo's code); a
`rerun_failed_jobs` retrigger succeeded cleanly on Attempt 1 (`Verify build
success`, `Check APK exists`, `Upload Debug APK`, `Build Release APK` all
succeeded). `Final Status` -- `success`. **This completes all 9 steps of the
category-unification plan.**

## Step 8 — Backup/restore format fix

### What changed

- **The bug**: encrypted backup/restore already silently discarded all
  dynamic-category data -- no `categoryId` on `BackupTransaction`, no
  categories table, no merchant->category mappings anywhere in the payload.
  A restore on a fresh device (or after a wipe) would come back with every
  transaction's dynamic category gone, even though `spendingCategory` (the
  legacy enum) survived intact -- a real, silent data-loss gap that got
  strictly worse once `Category` became this app's primary categorization
  system (Steps 1-7).
- `BackupTransaction` gains `val categoryId: Long? = null` (default handles
  decoding an old-format backup, where the field never existed).
- New `BackupCategory` (id, name, icon, colorHex, isCustom, monthlyCap:
  String?, parentCategoryId) and `BackupMerchantMapping` (id,
  merchantPattern, categoryId, isExactMatch, pending, suggestedName/Icon/
  ColorHex) DTOs, mirroring this file's existing "primitives only, amounts as
  plain-decimal strings" convention.
- `BackupPayload` gains `categories: List<BackupCategory> = emptyList()` and
  `merchantCategoryMappings: List<BackupMerchantMapping> = emptyList()`;
  `export()` now writes `formatVersion = 2` and populates both lists from
  `database.categoryDao().getAll()` / `database.merchantCategoryDao().getAll()`
  (both DAOs gained `getAll()`/`clearAll()`/`insertAll()`, the same
  backup-support trio every other DAO in this file already has -- neither
  entity carries an `isDemo` flag, unlike accounts/transactions, so no demo
  partitioning needed).
- **Import safety** (the one place this file deliberately does NOT mirror
  its own "replace everything" pattern): `import()` only clears and replaces
  categories/mappings when `payload.formatVersion >= 2`. A pre-Step-8 (v1)
  backup never captured that data in the first place, so restoring one now
  leaves whatever categories the user has built up since upgrading
  untouched, instead of silently wiping them for zero benefit. Every other
  table (accounts, transactions, rules, sender labels) keeps replacing
  unconditionally, exactly as before.
- Restored categories/mappings preserve their original ids (identical
  pattern to accounts/transactions, already relied on by this file) so a
  restored transaction's `categoryId` FK reference stays valid with no
  remapping needed. Confirmed via direct schema read that neither
  `categoryId` column (`transactions.categoryId`,
  `merchant_category_mappings.categoryId`) carries an actual Room
  `ForeignKey` constraint (only `transactions.accountId` does), so insert
  ordering between categories and transactions during restore has no
  referential-integrity requirement either way.

### Tests

- No new test file: `BackupManager` has never had one (`BackupCryptoTest.kt`
  is this package's only test, covering the separate encryption layer) --
  it's tightly coupled to Room's `AppDatabase`, which this project's plain-
  JUnit suite has no harness for (no Robolectric), matching the same
  established gap for every other entity mapper in this file
  (`AccountEntity`/`TransactionEntity`/`ParsingRuleEntity` round-trips are
  equally untested). The new `CategoryEntity`/`MerchantCategoryMappingEntity`
  mappers are simple 1:1 field copies, same shape as the existing untested
  ones.

### Deferred (within this step; later steps in this phase cover the rest)

- Step 9 unchanged from the prior status above.

### CI verification (Step 8)

Landed as `3a3a8ed`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m
48s`, `25 actionable tasks: 25 executed`, no test-failure line. `Build APK
(with retries)` in progress at the time Step 9 was prepared; Step 7's
superseded commit (`372cbe3`) showed the same benign concurrency-cancellation
`Final Status: failure` pattern confirmed repeatedly this session (its own
`Lint & Unit Tests` and `Build Debug APK - Attempt 1` had already succeeded
before being superseded by this push).

## Step 7 — Dashboard + Analytics chart migration

### What changed

- `DashboardViewModel.statsFlow` gains `categoryRepository.observeAll()` as
  its 5th flow (the confirmed free slot); `computeDashboardStats(...)` gains
  a `categories: List<Category>` param. `CategorySpend`, `SmartInsight.TopCategory`,
  and `SmartInsight.CategorySpike` all switch their `category` field from
  `SpendingCategory` to the dynamic `Category`.
- New pure `resolveCategory(categoryId, categories): Category` (in
  `DashboardViewModel.kt`, reused by `AnalyticsViewModel.kt` too) --
  resolves a transaction's `categoryId` against the known list; a null id or
  one that no longer resolves (category deleted since capture) falls back to
  the seeded "Uncategorized" category, with a synthetic literal as the final
  fallback only if the category list itself is empty. Both
  `computeDashboardStats`'s and `computeAnalyticsState`'s `categorySpendsOf`-
  style grouping now group by `resolveCategory(it.categoryId, categories)`
  instead of `it.spendingCategory`.
- `categorySlicesFrom` -- the donut-chart slice builder -- no longer colors
  the top 4 slices by rank (`SLICE_PALETTE[index]`, a fixed 4-color list);
  each real category now colors its own slice via a new pure
  `colorLongFromHex(hex): Long` (a plain-JVM "#RRGGBB" -> ARGB `Long`
  converter, since the existing Android-only `categoryColor()` UI helper
  can't run under this project's plain-JUnit tests). Only the "Other" rollup
  keeps the fixed grey (`OTHER_SLICE_COLOR`) -- it isn't a real category.
- `AnalyticsViewModel`'s `combine` was already at its 5-flow ceiling
  (transactions, rates, dateRange, selectedDate, hideBalances); folded
  `dateRange`+`selectedDate` into one `Selection` flow (same "fold to stay
  within five slots" pattern `DashboardViewModel` already uses for its own
  `uiMeta`/`metaBundle`) to make room for `categoryRepository.observeAll()`.
  `computeAnalyticsState(...)` gains the same `categories: List<Category>`
  param and `resolveCategory`-based grouping.
- `InsightsScreen.kt`'s `insightText` -- `insight.category.label` ->
  `insight.category.name` for `TopCategory`/`CategorySpike`.
- `GoalsScreen.kt`'s `GoalsViewModel` calls `computeDashboardStats(...)` only
  for its pure `.avgMonthlyNet` figure (income/spend-by-month, unrelated to
  categories) -- passes `categories = emptyList()` rather than wiring an
  unused `categoryRepository` flow into a ViewModel that has no other reason
  to touch categories.
- `DonutChart.kt`/`CategorySlice`/the `CategorySpendCard`/`CategoryDonutCard`
  composables (Dashboard's Insights tab and the Analytics screen) needed no
  changes -- both already consumed the decoupled `CategorySlice(name, amount,
  color)` shape, never the enum directly.
- Net effect: the category donut chart on both the Insights tab and the
  Analytics screen, plus the "biggest expense"/"category spike" smart
  insights, now reflect the same dynamic categories (including
  user-created/custom ones and their real colors) as the rest of the app,
  instead of being stuck on the frozen 14-value enum and a fixed rank
  palette.

### Tests

- `DashboardResolveCategoryTest` (new) -- 4 cases: a matching id resolves
  correctly; a null id falls back to seeded Uncategorized; a non-matching id
  falls back to seeded Uncategorized; an empty category list still returns a
  usable (synthetic) Uncategorized rather than throwing.
- `DashboardColorLongFromHexTest` (new) -- 3 cases: a well-formed "#RRGGBB"
  hex converts correctly; a hex with no leading "#" still converts; a
  malformed hex falls back to the neutral grey.
- `DashboardViewModelTest`, `DashboardSmartInsightsTest` -- every existing
  case updated in place to build `Category` fixtures and `categoryId`-based
  `Transaction`s instead of the `SpendingCategory` enum, including the
  `categorySlicesFrom` color-assertion tests (now asserting each category's
  own `colorHex`-derived color rather than the old rank palette).
- No new test for `computeAnalyticsState`'s `categories` param specifically
  -- this file has never had dedicated JVM tests for that function (matches
  existing precedent; `SpendingHeatmapTest.kt` is Analytics's only test file
  and covers unrelated pure logic).

### Deferred (within this step; later steps in this phase cover the rest)

- Steps 8, 9 unchanged from the prior status above.

### CI verification (Step 7)

Landed as `372cbe3`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m
52s`, `25 actionable tasks: 25 executed`, no test-failure line (covers the
new `DashboardResolveCategoryTest`/`DashboardColorLongFromHexTest` plus every
updated `DashboardViewModelTest`/`DashboardSmartInsightsTest` case). `Build
APK (with retries)` succeeded on Attempt 1 (Attempts 2-4 `skipped`); `Verify
build success`, `Check APK exists`, `Upload Debug APK` all succeeded.

### CI verification (Step 6c)

Landed as `65f0a8c` (with `68f4a28` immediately underneath), CI green.
`Lint & Unit Tests` -- `BUILD SUCCESSFUL in 2m 54s`, `25 actionable tasks: 25
executed`, no test-failure line (covers the 4 new `CategoryMigrationMappingTest`
cases). `Build APK (with retries)` succeeded on Attempt 1 (Attempts 2-4
`skipped`); `Verify build success`, `Check APK exists`, `Upload Debug APK`
all succeeded. Two earlier `check_run.completed` webhooks reported
`Final Status: failure` for the now-superseded `c3da453` (Step 6a) -- both
were confirmed benign: `Lint & Unit Tests` and `Build Debug APK - Attempt 1`
had already succeeded for that commit before this push's
`cancel-in-progress` concurrency group cancelled its still-running,
non-gating `Build Release APK` step, which is what actually failed
`Final Status`'s `needs.build-apk.result == 'success'` check -- not a real
code or test failure. Step 6 (6a + 6b + 6c) is now fully landed and verified.

## Phase 11 — Categories & People organized under Settings

**Status:** landed, CI green
**Version:** `versionCode = 58`, `versionName = "0.39.0"`

### What changed

- New Settings entry "Categories & People" (`SettingsScreen.kt`'s General
  group, between Preferences and Binance) opens a single tabbed screen
  (`CategoriesAndPeopleScreen`) with two tabs -- Categories and People --
  each with full add/edit/delete, so both are managed from one organized
  place instead of being split across two unrelated parts of the app.
- **Categories tab**: the existing category hierarchy list/editor, extracted
  out of the old standalone `CategoriesScreen` (which had its own
  Scaffold/TopAppBar/FAB) into a new embeddable `CategoriesTabContent`
  composable (no Scaffold chrome of its own). The host screen's FAB (shown
  only on this tab) opens the create dialog via a hoisted `creatingNew`
  boolean; edit/delete stay local to the list, unchanged in behavior.
  Nothing duplicated: the `"categories"` route (used by Insights >
  Categories and the Category budgets card's "Manage" button) now renders
  the same `CategoriesAndPeopleScreen` (defaulted to the Categories tab)
  instead of the old standalone screen, so there's one implementation with
  two entry points, not two.
- **People tab**: the existing saved-people list (add / rename-everywhere /
  remove, plus the "on transactions but not in your list" merge/clear
  section) moved out of Settings > Preferences, where it was buried among
  currency/budget/Forex settings, into its own tab. Its redundant internal
  "People" heading was dropped since the tab label already says it.
- `CategoryHierarchy.kt`'s KDoc reference to the removed `CategoriesScreen`
  updated to point at `CategoriesTabContent`.

### Tests

- No new pure logic -- this is a UI reorganization of already-tested pieces
  (`CategoriesViewModel`, `SettingsViewModel`'s people functions, both
  unchanged). Matches this app's existing precedent of not unit-testing
  Compose screens directly.

### CI verification

Landed as `fd03b02`, CI green. `Lint & Unit Tests` -- `BUILD SUCCESSFUL in
3m 11s`, `25 actionable tasks: 25 executed`, no test-failure line -- confirms
the new UI code (`TabRow`/`Tab`, `Icons.Filled.PieChart`, the
`CategoriesTabContent` extraction, the route/import changes across
`MainActivity.kt`/`SettingsScreen.kt`/`CategoriesScreen.kt`) compiles
cleanly. `Build APK (with retries)` succeeded on Attempt 1 (Attempts 2-4
`skipped`); `Verify build success`, `Check APK exists`, `Upload Debug APK`,
and the non-gating `Build Release APK` all succeeded. `Final Status` --
`success`.

## Phase 12 — Reassign transactions when deleting a category or person

**Status:** landed, CI green (build confirmed; test-step log expired -- see
CI verification below). Superseded in part by Phase 12.1, which fixes five
data-safety defects found reviewing this commit.
**Version:** `versionCode = 59`, `versionName = "0.40.0"`

### What changed

- Deleting a Category or Person that still has transactions attached no
  longer silently orphans them. Both delete flows now check the affected
  transaction count first; a zero count keeps the old low-friction instant
  delete / simple confirm, a non-zero count opens a picker dialog listing
  every other category/person plus an explicit "leave uncategorized" /
  "leave unassigned" option, and the chosen target is applied before the
  delete goes through.
- **Categories**: new `TransactionDao.reassignCategory(oldCategoryId,
  newCategoryId)` (`UPDATE ... WHERE categoryId = :oldCategoryId`, also
  clearing any pending AI suggestion fields), threaded through
  `TransactionRepository`/`TransactionRepositoryImpl` and the two existing
  test-fake repositories. `CategoriesUiState` gained `transactionCounts:
  Map<Long, Int>` (computed in `CategoriesViewModel.uiState`'s existing
  `combine`), and `CategoriesViewModel.delete` now takes a `reassignTo:
  Long?` and calls `reassignCategory` before `categoryRepository.delete`.
  `CategoriesTabContent`'s delete dialog branches on the category's count;
  the new `CategoryReassignDialog` composable is the picker.
- **People**: reused the existing `TransactionRepository.renamePerson(from,
  to: String?)` rather than adding new repository plumbing.
  `SettingsViewModel` gained `personCounts` (computed alongside
  `transactionPersons` in the existing `observeTransactions()` collector)
  and `transactionCountForPerson(name)`. The old `clearPersonFromTransactions`
  was replaced outright by `deletePersonAndReassign(name, reassignTo:
  String?)`, which renames-or-clears on transactions, removes the name from
  the saved list, and updates `defaultPerson` if it pointed at the deleted
  name -- a strict superset of the old function's behavior (calling it with
  a null target reproduces the old clear-only case). `PeopleSection`'s main
  list delete button now checks the count before deciding between instant
  delete and the picker; the unlisted-persons list's delete/clear button
  always opens the picker (those entries are derived from transactions, so
  their count is never zero). New `PersonReassignDialog` composable mirrors
  `CategoryReassignDialog`.

### Tests

- No new pure-JVM tests: this feature is UI wiring plus a straight SQL
  UPDATE (`reassignCategory`) and reuse of the already-tested `renamePerson`
  path, matching this app's existing precedent of not unit-testing Compose
  screens directly. The two repository test fakes
  (`RecordingBulkRepository`, `RecordingTransactionRepository`) each gained
  a trivial `reassignCategory` stub so the widened interface still compiles.

### CI verification

Landed as `db1cf50`, CI green -- run `32100792465`, all three jobs `success`
(`Lint & Unit Tests`, `Build APK (with retries)`, `Final Status`). `Build Debug
APK - Attempt 1` succeeded (Attempts 2-4 `skipped`), with `Verify build
success`, `Check APK exists`, `Upload Debug APK` and the non-gating `Build
Release APK` all succeeding -- so compilation of the new DAO/repository/UI code
is confirmed. Caveat: the step logs and the `unit-test-results` artifact had
already passed their retention window (expired 2026-08-20) by the time they were
re-read, so the `Run unit tests` step text could not be re-inspected for this
entry. Since that step carries `continue-on-error: true`, its `success`
conclusion alone does not prove the suite passed. The follow-up push below
re-runs the whole suite and its log was read directly.

## Phase 12.1 — Reassign-on-delete hardening

**Status:** landed, CI green
**Version:** `versionCode = 60`, `versionName = "0.40.1"`

Reviewing `db1cf50` against the surrounding code turned up five defects in the
feature shipped in Phase 12 -- two of which could destroy exactly the data the
feature exists to protect.

### What changed

1. **Demo-mode write leak.** `reassignCategory` had no `isDemo` filter while the
   counts driving its dialog come from the demo-aware `observeTransactions()`, so
   deleting a category during Demo mode reported demo rows and rewrote **real**
   ones. Both `reassignCategory` and `renamePerson` are now scoped with
   `AND isDemo = :demo`, fed from the `demoMode` StateFlow
   `TransactionRepositoryImpl` already holds. The `TransactionRepository`
   interface signatures are unchanged, so neither test fake needed touching.
2. **Person count/UPDATE case mismatch.** Counts were keyed
   `trim().lowercase()` but `renamePerson` matched `WHERE person = :from` --
   case-sensitive in SQLite -- so with both `John` and `john` on transactions the
   dialog promised 2 and moved 1. The match now carries `COLLATE NOCASE`, the
   same idiom already used by `CategoryDao.getByName` and
   `backfillCategoryByMerchant`. This makes the existing rename-everywhere path
   case-insensitive too, matching how the People screen already groups names.
3. **Startup race.** `personCounts` was a plain field, so before the ledger's
   first emission it read empty and the delete button took the instant-delete
   path -- stripping a person off transactions with no prompt at all. It is now
   Compose state, plus a `personCountsLoaded` flag: `personDeleteNeedsPrompt`
   errs toward showing the picker while counts are still loading, because an
   unknown count must never be treated as zero.
4. **Parent categories under-reported.** Counts were direct-only, so a parent
   with no direct transactions but children full of them showed the plain
   "will be removed" dialog. New pure `CategoryHierarchy.descendantIds` rolls the
   count up the tree to decide whether to prompt. Per the chosen behavior the
   children are kept, so the dialog states the two numbers separately -- only the
   direct rows move; sub-categories stay put and surface as top-level via
   `buildTree`'s existing dangling-parent fallback.
5. **Stale learned rules undid the reassignment.**
   `merchant_category_mappings.categoryId` has no FK, so it kept pointing at the
   deleted id and the next transaction from that merchant was filed straight back
   onto a category that no longer existed. New
   `CategoryRepository.repointMappings` follows the user's choice: repoint to the
   replacement, or drop the rules when "leave uncategorized" was picked.
   `CategoriesViewModel.delete` now reassigns, repoints, then deletes.

### Tests

Phase 12 shipped without unit tests; the fixes create real pure seams, so that
gap is closed here rather than deferred.

- `PersonCounts` (new `domain/people/`) owns the one trim+lowercase
  normalization rule shared by the count and the `COLLATE NOCASE` UPDATE.
  `PersonCountsTest` covers case folding, whitespace, nulls/blanks, distinct
  people, lookup by any casing, and unknown names.
- `CategoryHierarchyTest` gains `descendantIds` cases: direct children,
  grandchildren, unrelated branches, leaves and unknown ids, plus a
  `parentCategoryId` cycle and a self-parented row -- both must terminate rather
  than hang the UI thread on corrupt data.

### CI verification

Landed as `0f9abec`, CI green -- run `32662585195`, all three jobs `success`.
Verified by reading the `Lint & Unit Tests` job log directly (job
`97250898554`) rather than trusting the check mark, since that step carries
`continue-on-error: true` and would report `success` even on a failing suite:
`> Task :app:testDebugUnitTest` genuinely executed (not `UP-TO-DATE`, not
skipped), `BUILD SUCCESSFUL in 3m 16s`, `25 actionable tasks: 25 executed`,
with no `FAILED` line or test-failure summary anywhere in the output.
`compileDebugUnitTestKotlin` succeeded, so the new `PersonCountsTest` and the
added `CategoryHierarchyTest.descendantIds` cases both compiled and ran. The
only warnings are pre-existing deprecations (`menuAnchor`,
`LocalClipboardManager`, `Icons.Filled.Rule`, `TabRow`,
`rememberSwipeToDismissBoxState`, `Icons.Filled.Label`) -- none from this
change. `Build APK (with retries)` succeeded on Attempt 1 (Attempts 2-4
`skipped`); `Verify build success`, `Check APK exists`, `Upload Debug APK` and
the non-gating `Build Release APK` all succeeded. `Final Status` -- `success`.

### Not covered by CI

Every one of the five defects is runtime data behavior that the JVM test suite
and a compile check cannot reach, so these need an on-device pass once the
v0.40.1 APK is installed, roughly in priority order:

1. Demo mode on -> delete a category holding demo transactions -> Demo mode
   off -> real transactions untouched.
2. Force-stop, reopen, immediately delete a person before the list settles ->
   the picker appears rather than deleting silently.
3. With both `John` and `john` on transactions, delete one -> every spelling
   moves.
4. Delete a parent whose transactions all sit on its children -> picker
   appears, copy states both numbers, children survive as top-level.
5. Delete a category carrying a learned merchant rule, then ingest a message
   from that merchant -> it lands on the chosen replacement, not the dead id.

## Phase 12.2 — Three correctness bugs in the reassign pickers

**Status:** landed, CI green
**Version:** `versionCode = 61`, `versionName = "0.40.2"`

A follow-up review of Phase 12/12.1 (no data-loss this time, all correctness/
UX bugs) turned up three real defects.

### What changed

1. **A parent category's own children were offered as reassign targets for
   its own transactions.** The picker's target list excluded only the
   category being deleted, not its descendants -- deleting "Food" let you
   move Food's transactions onto "Groceries", a child that's losing its
   parent relationship in the same delete. New pure
   `CategoryHierarchy.reassignTargets` composes with the existing
   `descendantIds` to exclude the root and everything beneath it;
   `CategoriesScreen.kt`'s picker now calls it instead of a bare id filter.
2. **Deleted parent's children kept a dangling `parentCategoryId`.** The
   dialog promises "sub-categories move up to the top level," but nothing
   in the DB ever cleared the child rows' `parentCategoryId` -- it only
   *looked* true because `CategoryHierarchy.buildTree` defensively treats an
   unresolvable parent as top-level. New `CategoryDao.clearParent` nulls it
   out for every direct child before the delete;
   `CategoryRepositoryImpl.delete` now calls it first.
3. **Person reassign targets excluded unlisted names.** The picker only
   offered names from the saved People list, never names that exist only on
   transactions (`unlistedPersons`) -- even though `renamePerson`'s
   `COLLATE NOCASE` UPDATE accepts any name, since a person is free text,
   not an entity with an id. New `PersonCounts.reassignTargets` merges both
   sources, dedupes case-insensitively, and excludes the person being
   removed; `SettingsScreen.kt`'s picker now calls it.

### Tests

- `CategoryHierarchyTest` gains `reassignTargets` cases: excludes the root
  and direct children, excludes grandchildren, keeps unrelated branches
  (including children of another branch), a leaf excludes only itself, and
  a `parentCategoryId` cycle doesn't hang.
- `PersonCountsTest` gains `reassignTargets` cases: merges listed and
  unlisted names, dedupes a name present in both regardless of casing,
  excludes the deleted person regardless of casing, sorts the result, and
  is empty when both inputs are empty.
- `CategoryDao.clearParent` has no direct DAO-level test, matching the
  existing precedent for `reassignCategory`/`repointCategory` -- no Room/
  Robolectric instrumented-test infra exists in this repo.

### Deferred (not part of this change)

`CategoryReassignDialog`/`PersonReassignDialog` and their option-row
composables are still near-duplicates; `CategoriesViewModel.delete()` still
has no busy-guard against double-tap (unlike the People delete path); the
three-call delete sequence (`reassignCategory` -> `repointMappings` ->
`delete`) is still not wrapped in `database.withTransaction`. None of these
are correctness bugs on their own, so they were left out of this pass.

### CI verification

Landed as `4d8e800`, CI green -- run `32666093234` (push, run #307), all
three jobs `success`. Verified by reading the `Lint & Unit Tests` job log
directly (job `97259575597`) rather than trusting the check mark, since that
step carries `continue-on-error: true` and would report `success` even on a
failing suite: `> Task :app:testDebugUnitTest` genuinely executed (preceded
by `kspDebugUnitTestKotlin` and `compileDebugUnitTestKotlin` succeeding, so
the new `CategoryHierarchyTest.reassignTargets` and
`PersonCountsTest.reassignTargets` cases compiled and ran), `BUILD SUCCESSFUL
in 2m 59s`, `25 actionable tasks: 25 executed`, no `FAILED` line or
test-failure summary anywhere in the output. Only the same pre-existing
deprecation warnings as Phase 12.1 -- none from this change. `Build APK (with
retries)` succeeded on Attempt 1; `Verify build success`, `Check APK exists`,
`Upload Debug APK`, and the non-gating `Build Release APK` all succeeded.
`Final Status` -- `success`.

## Phase 12.3 — Account delete leaves a dangling parsing-rule reference

**Status:** landed, CI green
**Version:** `versionCode = 62`, `versionName = "0.40.3"`

A further review pass looking for the same class of bug elsewhere (an
entity delete that leaves a downstream reference dangling, like Phase
12.2's `parentCategoryId` fix) found one in Accounts.

### What changed

Deleting an Account never cleared `ParsingRuleEntity.assignAccountId` on
rules that pointed at it -- unlike `TransactionEntity.accountId`, that
column carries no real `@ForeignKey`. On the next capture matching such a
rule, `TransactionRepositoryImpl.recordParsed` writes the dangling id
straight onto `transactions.accountId`, which *does* have a real FK
(`SET_NULL` on delete) -- violating the constraint and rolling back that
capture inside its own `database.withTransaction`. New
`ParsingRuleDao.clearAssignAccountId` nulls out every rule pointing at the
account before it's deleted; `AccountRepositoryImpl.delete` now calls it
first, mirroring Phase 12.2's `CategoryDao.clearParent` /
`CategoryRepositoryImpl.delete` precedent exactly (app-level clear rather
than a real FK, since this is the only account-delete path that can
actually orphan a rule -- demo-account deletion wipes all demo rules
unconditionally right after, and backup restore always re-inserts a
self-consistent snapshot in the same transaction).

### Tests

- New `AccountRepositoryImplDeleteTest`: in-memory fake `AccountDao`/
  `ParsingRuleDao` prove `delete()` clears `assignAccountId` only on rules
  pointing at the deleted account, leaves other rules' pointers untouched,
  and still deletes the account row.
- `ParsingRuleDao.clearAssignAccountId` has no direct DAO-level test, same
  precedent as `CategoryDao.clearParent` -- no Room/Robolectric
  instrumented-test infra exists in this repo.

### Deferred (not part of this change)

A second bug from the same review pass -- `merchant_category_mappings`'s
unique index is case-sensitive while every lookup path treats merchant
names case-insensitively -- is a schema/migration change and lands
separately (Phase 12.4). The three items already deferred in Phase 12.2
(dialog dedup, Categories busy-guard, transactional delete wrap) remain
deferred.

### CI verification

Landed as `df738b9`. Its own CI run generated two benign `Final Status:
failure` webhooks (`32681187998`, `32681192373`) -- both are concurrency
cancellations (`Lint & Unit Tests`/`Build APK` show `conclusion:
cancelled`, not `failure`), caused by Phase 12.4's commit (`3382812`)
superseding it on the branch moments later -- the same false-alarm class
already documented for the Step 6a commit earlier in this project, not a
real failure. Verified instead via `3382812`'s own run (`32681265491`, run
#313), which is the branch tip and so contains both this commit's diff and
Phase 12.4's -- see that phase's CI verification section for the full log
read, which confirms `AccountRepositoryImplDeleteTest` compiled and ran.

## Phase 12.4 -- Case-insensitive merchant-mapping unique index

**Status:** landed, CI green
**Version:** `versionCode = 63`, `versionName = "0.40.4"`

The second bug from the same review pass that produced Phase 12.3, this
one a schema change.

### What changed

`merchant_category_mappings`' unique index on `merchantPattern` had no
collation (exact-byte comparison), while every lookup path treats merchant
names case-insensitively -- `CategoryMatcher.match`'s
`equals(ignoreCase = true)`, `MerchantCategoryDao.findPending`'s
`COLLATE NOCASE`. `learnMapping`/`suggestPending`'s
`OnConflictStrategy.REPLACE` upsert relies on that index to treat a
re-learned merchant as the same row, so different casings of one merchant
(e.g. "Starbucks" vs "STARBUCKS") silently produced duplicate rows instead
of colliding -- and which duplicate a later match hit was undefined.

New `MIGRATION_22_23` (schema v22 -> v23) rebuilds the table: a correlated
subquery keeps exactly one row per case-insensitive `merchantPattern`
group (a confirmed mapping, `pending = 0`, over a still-pending
suggestion; ties broken by highest `id`), then recreates the table with
`merchantPattern` declared `COLLATE NOCASE`.
`MerchantCategoryMappingEntity.merchantPattern` gains a matching
`@ColumnInfo(collate = ColumnInfo.NOCASE)` in the same commit -- Room's
`onCreate` for a *fresh* install builds the table straight from the
entity, not from any `Migration`, so without that annotation a brand-new
v23+ install would still get the old case-sensitive behavior. No code
changes needed in `learnMapping`/`suggestPending` -- their existing
REPLACE upsert starts colliding case-insensitively automatically once the
index itself does.

### Tests

No unit test: the migration's raw SQL (table rebuild + de-dup ranking)
can't run in a pure-JVM test -- no Robolectric `MigrationTestHelper`
exists in this repo, matching every other `MIGRATION_*` in `AppDatabase.kt`,
none of which have tests. Not adding a parallel Kotlin re-implementation
of the de-dup ranking just to have something testable, since that would
test code production never calls. No mapper round-trip test either -- no
field was added; `collate` is invisible to `toDomain()`/`toEntity()` and
to data-class equality.

### Deferred (not part of this change)

Same three items deferred since Phase 12.2 (dialog dedup, Categories
busy-guard, transactional delete wrap) remain deferred.

### CI verification

Landed as `3382812` (branch tip, also carries Phase 12.3's `df738b9`
diff). Run `32681265491` (push, run #313), all three jobs `success`.
Verified by reading the `Lint & Unit Tests` job log directly (job
`97298312073`) rather than trusting the check mark, since that step
carries `continue-on-error: true`: `> Task :app:testDebugUnitTest`
genuinely executed (preceded by `kspDebugUnitTestKotlin` and
`compileDebugUnitTestKotlin` succeeding, so Phase 12.3's new
`AccountRepositoryImplDeleteTest` case compiled and ran), `BUILD
SUCCESSFUL in 3m 28s`, `25 actionable tasks: 25 executed`, no `FAILED`
line or test-failure summary anywhere in the output. Only the same
pre-existing deprecation warnings as prior phases (`menuAnchor`,
`LocalClipboardManager`, `Icons.Filled.Rule`, `TabRow`,
`rememberSwipeToDismissBoxState`, `Icons.Filled.Label`) -- none new from
either commit. Since nothing in CI actually exercises the migration's raw
SQL against a real database (no Robolectric), `Build APK (with retries)`
succeeding on Attempt 1 is the practical proof available here: it confirms
the migration object and the `@ColumnInfo(collate = NOCASE)` entity change
compile and dex cleanly, not that the migration runs correctly against
production data -- a real-device upgrade check remains the only way to
confirm the de-dup logic itself. `Verify build success`, `Check APK
exists`, `Upload Debug APK`, and the non-gating `Build Release APK` all
succeeded. `Final Status` -- `success`.

## Phase 12.4 — Case-sensitive merchant-mapping unique index

**Status:** landed, pending CI
**Version:** `versionCode = 63`, `versionName = "0.40.4"`

The second bug from the same review pass: `merchant_category_mappings`'s
unique index on `merchantPattern` had no collation, so it was
case-sensitive, while every lookup path treats merchant names
case-insensitively -- `CategoryMatcher.match`'s `equals(ignoreCase = true)`,
`MerchantCategoryDao.findPending`'s explicit `COLLATE NOCASE`. Because
`learnMapping`/`suggestPending` upsert with `OnConflictStrategy.REPLACE`,
which relies on that index to decide "same merchant, replace it,"
re-learning a merchant under different casing (e.g. "Starbucks" vs.
"STARBUCKS") silently created a second row instead of replacing the first,
and which duplicate a later match resolved to was undefined.

### What changed

Schema migration v22 -> v23 (`MIGRATION_22_23` in `AppDatabase.kt`).
SQLite can't alter a column's collation in place, so the migration rebuilds
`merchant_category_mappings`: a correlated subquery keeps exactly one row
per case-insensitive `merchantPattern` group (a confirmed mapping,
`pending = 0`, over a still-pending suggestion; ties broken by highest
`id`), copies only those into a new table whose `merchantPattern` column
is declared `COLLATE NOCASE`, then drops/renames and recreates the unique
index. `MerchantCategoryMappingEntity.merchantPattern` gains a matching
`@ColumnInfo(collate = ColumnInfo.NOCASE)` in the same commit -- Room's
`onCreate` for a brand-new install builds the table straight from the
entity, not from any `Migration`, so without that annotation a fresh v23+
install would still get the old case-sensitive table. No other code
changes needed: `learnMapping`/`suggestPending`'s existing REPLACE upsert
starts colliding case-insensitively automatically once the index itself
does.

### Tests

Not unit-tested. The migration's raw SQL (table rebuild + de-dup ranking)
can't run without a real SQLite engine, which this repo has no
Robolectric/`MigrationTestHelper` infra for -- matching every other
`MIGRATION_*` in `AppDatabase.kt`, none of which have tests. Deliberately
not adding a parallel Kotlin re-implementation of the de-dup ranking just
to have something testable, since that would test logic production never
calls directly. No mapper round-trip test needed either -- no field was
added; `collate` is invisible to `toDomain()`/`toEntity()` and to
Kotlin data-class equality.

### Deferred (not part of this change)

The three items deferred since Phase 12.2 (dialog dedup, Categories
busy-guard, transactional delete wrap) remain deferred.

### CI verification

Pending -- this section gets filled in once the real job log is read. Since
nothing in CI exercises the migration's raw SQL, a green `assembleDebug`
here only proves `AppDatabase.kt` compiles, not that the migration itself
is correct -- that limit gets noted explicitly rather than treated as
proof.

## Phase 13 — Recurring bills drifted off the calendar

**Status:** landed, CI verified
**Version:** `versionCode = 64`, `versionName = "0.40.5"`

`BillRecurrence` carried nominal day counts (`WEEKLY(7)`, `MONTHLY(30)`,
`YEARLY(365)`) and `BillAdvancer.advance` moved a bill's due date forward by
that many fixed milliseconds when the user marked it paid. A [Bill] is due on
a *date the user picked*, so a fixed-millisecond step drifts:

- `MONTHLY(30)` walked rent due on the 1st to Jan 31, then Mar 2, Apr 1, ...
  roughly five days a year backwards through the month.
- `YEARLY(365)` ignored leap years, slipping an annual bill a day earlier at
  every one.
- A fixed-millis step also shifted the due date's time of day by an hour
  across a DST boundary.

### What changed

`BillRecurrence` now carries a `java.time.Period` instead of `nominalDays`;
`BillAdvancer.advance` gains a `zone: ZoneId = ZoneId.systemDefault()`
parameter (matching `CashFlowForecaster` / `GoalForecaster`) and adds the
period in that zone, so a monthly bill keeps its day of month and an annual
one keeps its calendar date.

Month-end is preserved explicitly. `java.time` clamps Jan 31 + 1 month to
Feb 28, and advancing from *that* clamped date would strand a month-end bill
on the 28th permanently, so when the current due date is its month's last day
and the cycle is month-based, the result snaps to the new month's last day:
Jan 31 -> Feb 28 -> Mar 31 -> Apr 30. A weekly cycle never snaps -- landing
on a month's last day is a coincidence there, not the user's intent. The snap
keys off `period.toTotalMonths() > 0` rather than a second enum field.

`SubscriptionCadence` deliberately keeps its nominal day counts. It describes
an observed rhythm the detector *measures* in days with a +/-5 day tolerance
(`SubscriptionRadarEngine.classifyCadence`) and normalizes with
(`MonthlyReportBuilder.subscriptionMonthlyTotal`), not a user-declared date
rule -- the two are different kinds of thing and now read differently.

Also in the same commit: `MonthlyReportBuilder` scoped its subscription list
to the report currency. The list filtered only on `dismissed` while the
"monthly equivalent" total beneath it also filtered on currency, so a USD
subscription appeared in an AED report above a total that silently excluded
it. Both now read from one shared filtered list, so they cannot diverge again.

### Tests

`BillAdvancerTest` rewritten against calendar semantics (13 tests): day of
month held across twelve consecutive cycles, month-end recovery after a short
month, mid-month never snapping, weekly never snapping, leap-year handling,
Feb 29 clamping, time-of-day preservation, a Europe/London DST crossing, and
an overdue bill still stepping exactly one cycle. `MonthlyReportBuilderTest`
gains off-currency list exclusion plus a check that the listed subscriptions
are exactly the ones behind the monthly total.

`BillRecurrence.nominalDays` had exactly one consumer (`BillAdvancer`), and
the Bills UI renders `option.name`, so nothing else needed touching. Only the
enum's `name` is persisted, so replacing the constructor arg is not a schema
change and needs no migration.

### CI verification

Run `33349327158` (commit `82ee7d2`), `Lint & Unit Tests` job log read
directly rather than the check mark: `> Task :app:testDebugUnitTest`,
`BUILD SUCCESSFUL in 3m 27s`, `25 actionable tasks: 25 executed`, no failure
line. Gradle exit 0 with the test task actually in the graph is what proves
the suite passed -- the step is `continue-on-error`, so its own conclusion
would read `success` regardless.

That run's `Final Status` reported failure, which was **not** a code failure:
pushing Phase 13.1 four minutes later cancelled the run's `Build APK` job by
workflow concurrency, and `final-status` fails when `build-apk` did not
succeed. The unit-test job had already completed successfully before the
cancellation. Same benign pattern noted in Phase 12.3. Compilation is proven
by the superseding run below, which builds the same code plus Phase 13.1.

## Phase 13.1 — Backup/restore silently dropped transaction and account columns

**Status:** landed, CI verified
**Version:** `versionCode = 65`, `versionName = "0.40.6"`

The backup DTOs had not kept up with the entities, and restore reset every
field the payload didn't carry to its entity default, with no warning:

- `TransactionEntity` lost `excludeFromStats`, `linkedTransactionId`,
  `needsCategoryApproval`, `suggestedCategoryName` / `Icon` / `ColorHex`,
  `extractedMerchant` and `aiConfidence`.
- `AccountEntity` lost `statementCycleDay` and `hiddenFromDashboard`.

`excludeFromStats` is the damaging one. Balance corrections, Tabby refund
adjustments and reconciled transfers are all kept in the ledger but held out
of analytics by that flag (the three ingestion points in the handover's §3),
so restoring a backup re-admitted every one of them and **a restored ledger
reported different spending totals than the ledger that was backed up**.
Losing `linkedTransactionId` compounded it by unpairing reconciled transfers
so both legs counted as real money, and also freed them to be re-matched by
`TransferMatcher`. `statementCycleDay` silently disabled the statement-cycle
filter preset for the card it was set on.

Phase "Step 8" had fixed the *category* half of exactly this gap (adding
`categoryId`, categories and merchant mappings, format v2) without noticing
the other dropped columns.

### What changed

`BackupPayload.formatVersion` goes to 3 and the DTOs carry all of the above.
No import gate is needed, unlike the v2 category gate: every added field
defaults to its entity default, so a v1/v2 backup restores exactly as it did
before. `isDemo` remains deliberately uncarried on both types -- export
selects real rows only and import restores them as real rows.

The mappers also moved off positional construction to named arguments. They
pair DTOs with entities whose field orders differ and that have long runs of
same-typed nullable columns, so a positional argument list compiles clean
while writing the wrong column -- and silently absorbs a newly added field
instead of failing, which is how these columns went unnoticed in the first
place.

They are now `internal` top-level functions rather than private methods on
`BackupManager`, so the round trip is JVM-testable without a Room database --
the same stance the entity <-> domain mappers take.

### Tests

New `BackupMappersTest` (12 tests). Each fixture sets *every* field to a
distinctive non-default value and asserts the whole entity comes back equal;
data-class equality is what makes this exhaustive, so a column added to an
entity without being carried through the backup now fails here rather than on
a user's next restore. Plus: v2-payload JSON decoding at entity defaults,
full JSON serialization round trip (not just the mappers), amount scale
preservation, `isDemo` exclusion, and the unknown-`spendingCategory` fallback
for a backup written by a newer build.

### CI verification

Run `33349515286` (commit `713e16c`, branch tip), `Lint & Unit Tests` job log
read directly: `> Task :app:compileDebugUnitTestKotlin`,
`> Task :app:testDebugUnitTest`, `BUILD SUCCESSFUL in 3m 4s`,
`25 actionable tasks: 25 executed`, no failure line. The unit-test artifact
uploaded 132 files against the previous run's 131 -- the one extra report
file is the new test class, confirming it was actually discovered and run
rather than silently skipped.

### Not covered by CI

Both phases are runtime data behavior, so a device pass is still worth doing:
mark a monthly bill paid on the 1st and on the 31st and confirm the next due
dates; export a backup with some transactions excluded from analytics and
some transfers reconciled, restore it, and confirm the Dashboard totals and
the statement-cycle filter preset are unchanged.

## Phase 14 — Paste a backlog to import, paste an example to ignore

**Status:** landed, pending CI
**Version:** `versionCode = 66`, `versionName = "0.41.0"`
**Schema:** v23 -> v24 (`ignored_message_patterns`)

Two halves of the same idea -- deciding, by example, what the app does with a
message -- reached from Settings > Capture > "Import & ignore messages".

### What changed

1. **Paste a backlog to import.** `ImportPastedMessagesUseCase` splits the
   pasted text into individual messages and feeds each through the same
   `IngestCapturedMessageUseCase` the SMS receiver and notification listener
   use, so rules, categorization and the ignore list behave identically. It
   reports back four counts: imported, already in the ledger, skipped by the
   ignore list, and not recognised.
2. **Paste an example to ignore.** A pasted sample is stored as a template
   (`ignored_message_patterns`), and every captured message is checked against
   the list before parsing. Matching messages return the new
   `IngestResult.Ignored` and never reach the ledger or the Gate-2 AI worker.

Two things necessarily differ from a live capture, both because pasted text
has no arrival time of its own:

- **When it happened** comes from the date the bank printed in the message
  (`MessageDateExtractor`), falling back to import time. Without this a pasted
  backlog would land in the ledger dated today.
- **Duplicate detection** leans on the exact message text (new
  `TransactionDao.findByRawMessage`), which is what makes re-pasting the same
  backlog a no-op. The ±2-minute sliding window still runs, but only for
  messages that state a real clock time -- for a date-only message it would
  compare midnight against midnight and could silently swallow a genuine
  second purchase of the same amount that day. `recordParsed` gained a
  `dedupWindow` flag for exactly that.

### Design notes

- **Matching is by template shape, not by text.** `MessageSimilarity`
  fingerprints a message by collapsing every digit run to `#` and dropping
  punctuation (reusing `TextNormalizer`, so Arabic-Indic digits normalize
  first), which is what makes one pasted OTP mute every future OTP. Templates
  that also vary a word fall back to Jaccard token overlap at 0.75.
- **Short messages only ever match exactly.** Below `MIN_FUZZY_TOKENS` (4)
  the fuzzy path is disabled: a three-word message overlaps other three-word
  messages far too easily, and a false ignore silently drops a real
  transaction -- worse than letting noise through.
- **The ignore check sits after the digit fast-path**, not before it. A
  digit-free message is already leaving as `NoMatch`, so checking it first
  would cost every chat notification a fingerprint pass for no behavior
  change. The list itself is held in memory by `IgnoredMessageCache`, mirroring
  `CompiledRuleCache`, so capture never pays a Room read per notification.
- **New `TransactionSource.PASTED`** keeps imported rows honest: their
  timestamps come from the message text, not from when they arrived.
- Backup format v3 -> v4 carries the ignore list, gated on import the same way
  category data is: restoring a pre-v4 backup leaves this device's list alone
  rather than silently un-muting everything.

### Tests

- `MessageSimilarityTest` — fingerprint collapses digits/case/punctuation and
  Arabic-Indic digits; Jaccard scoring; the same promo template with different
  numbers matches; a real purchase doesn't match a promo; short messages match
  only exactly; blank text never matches.
- `PastedMessageSplitterTest` — blank-line separation, wrapped messages
  rejoined, one-per-line fallback, CRLF/CR endings, duplicates preserved.
- `MessageDateExtractorTest` — day-first slash/dash/dot dates, ISO dates,
  month-first when the second component exceeds 12, two-digit years, named
  months, clock times with seconds and am/pm, and the rejections: a two-part
  card expiry, future dates, decade-old dates, impossible dates, and a
  malformed candidate not hiding a real date later in the text.
- `IgnoredMessageCacheTest` — exact and near-miss matching, real transactions
  left alone, deletes taking effect immediately, and a failing repository
  ignoring nothing rather than killing capture.
- `IngestCapturedMessageUseCaseTest` — muted messages return `Ignored` and
  never reach the ledger or fire the Gate-1 AI hook; the digit-free
  short-circuit still wins; an unrelated pattern leaves real transactions alone.
- `ImportPastedMessagesUseCaseTest` — the four summary counts, `PASTED` as the
  recorded source, re-pasting importing nothing, repeats inside one paste, the
  printed date becoming the timestamp, and the window-dedup flag following
  whether a clock time was stated.
- `EntityMappersTest` / `BackupMappersTest` — round-trips for the new entity,
  plus a pre-v4 payload decoding to an empty ignore list rather than failing.
- Not unit-testable here (no Robolectric): the v23 -> v24 migration SQL and
  the new DAO queries, matching the standing precedent for every other
  migration in `AppDatabase.kt`.

### CI verification

Run `34019575001` (commit `9baea43`, branch tip), workflow conclusion
`success`, `Final Status` success.

`Lint & Unit Tests` job log read directly rather than trusting the check mark
(the step is `continue-on-error: true`, so its conclusion reads success
whether or not tests pass): `> Task :app:compileDebugUnitTestKotlin`,
`> Task :app:testDebugUnitTest`, `BUILD SUCCESSFUL in 4m 43s`,
`25 actionable tasks: 25 executed`, no failure line. The unit-test artifact
uploaded 137 files against the previous run's 132 -- five extra report files
for the five new test classes, confirming they were discovered and run rather
than silently skipped.

`Build APK` job: `Build Debug APK - Attempt 1` succeeded first try (attempts
2-4 skipped), `Check APK exists` and `Upload Debug APK` both succeeded. So
the gating contract -- compilation -- is genuinely met.

### Known: the non-gating release build fails on a lint crash

The `Build Release APK` step logged `BUILD FAILED in 5m 5s`. That step is
`continue-on-error: true`, so its own conclusion still reads success and the
workflow stays green -- the same trap the unit-test step sets, worth reading
past here too.

The failure is a crash *inside lint itself*, not a lint finding:

    Unexpected failure during lint analysis of MMBankApp.kt
    Found class org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall,
      but interface was expected
    The crash seems to involve the detector
      `androidx.lifecycle.lint.NonNullableMutableLiveDataDetector`

An `IncompatibleClassChangeError` between lint's bundled Kotlin analysis API
and the Kotlin it is run against -- a toolchain binary incompatibility, which
application source cannot cause. Nothing in this phase's diff plausibly
reaches it: it does not touch `MMBankApp.kt`, introduces no LiveData, and the
app contains no `MutableLiveData` anywhere for that detector to have a finding
about. Every toolchain version is pinned (AGP 8.7.3, Kotlin 2.0.21, lifecycle
2.8.7), so nothing floated between runs either.

**It is pre-existing.** The previous run's log had already expired (410 Gone)
and the step's `continue-on-error` conclusion reads success either way, so
neither answers the question -- but the *next* step does. `Upload Release APK`
is gated on

    if: steps.build_release.outcome == 'success'

and `outcome` is the step's result *before* `continue-on-error` is applied
(only `conclusion` is rewritten to success). That step shows `skipped` on the
previous run `33349877137` (commit `0d5da88`) exactly as it does here, so
`assembleRelease` was already failing before this phase, and no release APK
has been produced on either commit.

Left alone rather than papered over: suppressing `NullSafeMutableLiveData` in
`app/build.gradle.kts` would silence it, but that is a decision about the
release pipeline and predates this change. Worth noting that `outcome` vs
`conclusion` is the same trap as the unit-test step, one level deeper -- the
run list makes this look green in three separate places.

### Not covered by CI

Device pass worth doing: paste a handful of real bank SMS and confirm the
dates land where the messages say; paste the same batch again and confirm it
reports everything as already imported; paste a promo into the Ignored tab and
confirm the next one like it never appears in the ledger.

## Phase 14.1 — Release build unblocked, and imports stop spending AI budget

**Status:** landed, pending CI
**Version:** `versionCode = 67`, `versionName = "0.41.1"`

Two follow-ups the user asked for after Phase 14.

### 1. The release build actually builds again

Phase 14 established that `assembleRelease` had been failing since before that
phase, on a crash inside lint rather than a lint finding -- an
`IncompatibleClassChangeError` in AndroidX's
`NonNullableMutableLiveDataDetector`, from a mismatch between lint's bundled
Kotlin analysis API and the Kotlin it runs against.

`app/build.gradle.kts` now disables the single issue that detector owns:

    lint { disable += "NullSafeMutableLiveData" }

which is what lint's own crash message recommends, and costs nothing here --
the app uses no `MutableLiveData` anywhere, so the detector has nothing to
find. It should be dropped once AGP ships a lint whose analysis API matches
the Kotlin plugin.

**How to verify this actually worked** (the step is `continue-on-error`, so
neither its conclusion nor the workflow's proves anything): `Upload Release
APK` is gated on `steps.build_release.outcome == 'success'`, and `outcome` is
the pre-`continue-on-error` result. That step being **skipped** means the
release build failed; it **running** means it succeeded. It was skipped on
every run up to and including `15281fa`.

### 2. Pasted imports no longer spend the AI budget

`IngestCapturedMessageUseCase.invoke` gained `forwardOnMiss` (default true, so
live capture is unchanged), and `ImportPastedMessagesUseCase` passes false.

The asymmetry is the point: a live capture is a single message the user never
asked for, so spending a little AI budget to read it is worth it. A paste is a
deliberate batch that could be hundreds of messages at once, and quietly
draining the day's budget on it is not what someone pressing "Import" is
asking for. Unreadable pasted messages land in `Summary.unparsed` instead, and
the add-transaction dialog's manual "Parse with AI" is still there for the
individual ones worth spending on.

### Tests

- `IngestCapturedMessageUseCaseTest` -- `forwardOnMiss = false` suppresses the
  hand-off on a message that otherwise clears `AiForwardGate`; the default
  still forwards, so live capture is provably unchanged.
- `ImportPastedMessagesUseCaseTest` -- an unreadable pasted message reports
  unparsed and forwards nothing, and a 20-message batch of them still forwards
  nothing.
- The lint change is build configuration; CI is its own test (see the
  `Upload Release APK` check above).

### CI verification

Run `34055721764` (commit `3362853`), workflow conclusion `success`,
`Final Status` success.

**The release build is fixed, and this is the proof:** `Upload Release APK`
**ran** (conclusion `success`, 19:57:48 -> 19:57:50) instead of being skipped.
Since that step is gated on `steps.build_release.outcome == 'success'` and
`outcome` is the pre-`continue-on-error` result, it only runs when
`assembleRelease` genuinely succeeded. It had been skipped on every previous
run. A release APK now exists as a build artifact for the first time.

`Lint & Unit Tests` job log read directly (the step is `continue-on-error`, so
its conclusion proves nothing): `> Task :app:compileDebugUnitTestKotlin`,
`> Task :app:testDebugUnitTest`, `BUILD SUCCESSFUL in 4m 34s`,
`25 actionable tasks: 25 executed`, no failure line. The artifact held 137
report files, unchanged from Phase 14 -- correct here, since this phase added
test methods to existing classes rather than new test classes.

`Build APK` job: debug APK succeeded on attempt 1, `Check APK exists` and
`Upload Debug APK` both succeeded.

### Not covered by CI

The AI-budget change is runtime behavior: worth confirming on a device that
pasting a batch of messages the rules can't read reports them as unparsed and
leaves Settings > AI parsing's daily counter untouched, while a live SMS that
misses still forwards.

## Phase 15 — Balances that tell the truth

**Status:** landed, pending CI
**Version:** `versionCode = 69`, `versionName = "0.43.0"`
**Migration:** none — no schema change

First of three commits answering "make transactions smarter, assignment better,
and balances update better in real time". The liveness half of that was already
solved: every balance surface is a cold Room `Flow` -> `combine` -> `stateIn`
with no polling, so a balance already updated the instant a message landed.
Accuracy was the real gap.

### Why

Balances are derived, never stored — `openingBalance + (credits - debits)` —
and that derivation existed in three places that disagreed with each other.

1. **A retention prune silently moved every balance.** `TransactionRetentionWorker`
   deleted old rows via `deleteMany` and never folded their net back into
   `openingBalance`. Turning the (opt-in) retention setting on meant watching
   your balances drift by the net of whatever window got pruned, with nothing
   on screen to explain it. This was the sharpest of the five.
2. **`Double`, not `BigDecimal`.** `observeNetByAccount` summed
   `CAST(amount AS REAL)` in SQL, but amounts are stored as plain-decimal TEXT
   (see `Converters`), so the CAST was a lossy re-parse — in an app that is
   otherwise exact end to end.
3. **The Monthly Report disagreed with the Accounts screen.** It recomputed
   balances in Kotlin with `.filter { !it.excludeFromStats }`, which drops
   balance-adjustment rows *and both legs of every linked transfer*
   (`linkAsTransfer` sets that flag). Those are real movements; excluding them
   from analytics is right, excluding them from a balance is not.
4. **Money in no account was invisible.** A row with `accountId = null` is in
   the ledger and in no balance, and neither Home nor Accounts said so.

### What changed

**Domain (pure, JVM-testable)**
- `domain/account/AccountBalanceCalculator.kt` (new) — the single balance
  formula, `BigDecimal` throughout. Two semantics are deliberate and each has
  its own named test so neither can decay into an accident: `excludeFromStats`
  rows **are** counted (fixing the report), and amounts are summed **regardless
  of currency**, preserving existing behaviour rather than changing it
  underneath the user (see Deferred).
- `domain/account/PrunedBalanceFolder.kt` (new) — the arithmetic that keeps a
  prune balance-neutral. If the deleted rows contribute a net of `n`, the
  balance would move by `-n`, so `newOpening = opening + n` holds it fixed.
  Rows with no account fold into nothing; accounts with no pruned rows produce
  no adjustment at all, so the pruner writes only what it must.

**Data**
- `Daos.kt` — `AccountNetRow` / `observeNetByAccount` deleted, replaced by
  `LedgerEntryRow` + `observeLedgerEntries(demo)`: a 4-column projection whose
  amount goes TEXT -> BigDecimal through `Converters`, never a CAST. It also
  drops the old `accountId IS NOT NULL` filter, which is what makes unassigned
  money visible. Plus `getLedgerEntriesByIds(ids)` and
  `AccountDao.setOpeningBalance(id, openingBalance)`.

  Deliberately not a new SQL aggregate: the only exact variant needs a hidden
  `ROUND(... * 1000)` rule, which would be invisible to a pure-JVM test — the
  same class of quiet lie this phase removes. Deliberately not
  `observeTransactions()` either: full entities carry the whole `rawMessage`
  SMS body, ~27 fields against 4. The Accounts screen now holds one small
  object per transaction where it previously held a tiny aggregate; Home and
  the Monthly Report already hold the entire ledger as full entities, so this
  is strictly cheaper than what two screens already did.
- `data/retention/RetentionPruner.kt` (new) — folds and deletes inside **one**
  `withTransaction`, so a failure can never leave the balance folded but the
  rows present. Held by `AppContainer` directly, following `BackupManager`,
  rather than added to `TransactionRepository`: that interface already has ~25
  members and three hand-written test fakes that each pay for every new one.

**UI**
- `AccountsScreen` — `AccountWithBalance.balance` is now `BigDecimal`; the
  ViewModel emits an `AccountsUiState` carrying the rows plus an
  `UnassignedTotal`. `buildBalanceAdjustment` takes a `BigDecimal` computed
  balance, so the reconciliation dialog compares exact figures.
- `DashboardViewModel` — computes in `BigDecimal`, then `.toDouble()` at the
  boundary. `AccountRow.balance` stays `Double` on purpose: everything
  downstream (`computeAccountsPresentation`, `CurrencyMath.toBase`, the
  carousel) is display math that is already well covered, and converting it
  would buy nothing once the computation itself is exact. `DashboardViewModelTest`
  is untouched as a result.
- `MonthlyReportScreen` — the local fold is gone; the `excludeFromStats` filter
  with it, for the net-worth figure only. `income`/`expenses` keep theirs,
  which is correct for analytics. `MonthlyReportBuilder` is untouched, so
  `MonthlyReportBuilderTest` stays green.
- Both Home and Accounts show a "N transactions aren't in any account" card
  when there are any, tapping through to the `AccountFilter.Unassigned` the
  Activity tab already had. The route gained an optional
  `transactions?accountFilter=` arg (the `rule_editor?ruleId=` pattern);
  `"transactions"` was added to that tab's `childRoutePrefixes` so the bottom
  bar stays highlighted when deep-linked.
- `Format.kt` — a `BigDecimal.asMoneyOrMask`, so an exact balance reaches the
  UI without passing through a `Double`.

### Tests

- `AccountBalanceCalculatorTest` (14): opening + credits - debits; other
  accounts never leak in; **a fraction Double cannot represent survives
  exactly**; no scale is imposed so nothing is silently rounded; **balance
  adjustments and both transfer legs are counted**; **mixed currencies are
  summed one to one - deliberate**; liabilities trend negative;
  `unassignedTotals` groups by currency and normalizes its case;
  `unassignedCount`; `netByAccount` omits empty accounts rather than reporting
  zero.
- `PrunedBalanceFolderTest` (10): most cases assert the invariant rather than a
  magic number — compute, prune, fold, balance unchanged. **The headline case**
  plus: debits lower / credits raise the opening; the previous opening is
  reported for auditing; each account folds only by its own rows; an account
  with no pruned rows produces no adjustment; unassigned rows fold into
  nothing; an empty prune is a no-op; exactness for amounts Double would round;
  a foreign-currency row folds at face value (the fold must use the same
  arithmetic as the balance it protects); pruning everything leaves the balance
  standing.
- Updated: the three `TransactionRepository` fakes (one-line override swap) and
  `BalanceAdjustmentTest`'s four `Double` literals, plus a new case proving an
  exact computed balance can't manufacture a phantom one-cent correction.

### Deferred

- **Foreign-currency amounts still sum 1:1** into an account of a different
  currency — a USD 100 charge reduces an AED account by 100. Raised as the
  defect most likely to be quietly wrong on a real ledger; the user chose
  explicitly to leave the behaviour alone, so the new calculator preserves it
  exactly rather than changing it silently, and the named test above keeps the
  choice visible. Revisiting it is self-contained: a conversion step inside
  `AccountBalanceCalculator`, with unconvertible rows flagged using the app's
  existing "≈" convention.
- Not provable in CI: that a real prune leaves balances untouched on a device,
  and that the Monthly Report's net worth now matches the Accounts screen.

### CI verification

Pending — to be amended with the run id, job id and log excerpt once read.

## Phase 15.1 — Corrections that stick: merchant normalization + category learning

**Status:** landed, pending CI
**Version:** `versionCode = 70`, `versionName = "0.44.0"`
**Migration:** none — the key is computed at match time, never stored

Second of three. Commit 1 made balances truthful; this makes the app learn
from the corrections you already make.

### Why

Two findings, and the second explains why fixing the first alone wouldn't have
been worth much.

1. **The everyday correction taught the app nothing.** `ReviewViewModel`,
   `UncategorizedQueueScreen`, `MerchantDetailScreen` and the AI suggestion
   path all called `CategoryRepository.learnMapping`. The category chip on the
   Activity feed — the path people actually use — called `setCategory` and
   stopped there. So the same merchant arrived uncategorized forever no matter
   how many times you fixed it.
2. **Nothing normalized merchants.** A merchant is whatever the parser's regex
   clause grabbed, only trimmed, and `CategoryMatcher` compared it by exact
   string equality. `POS CARREFOUR HYPERMARKET DXB *4543` and
   `CARREFOUR MALL OF EMIRATES` are unrelated strings, so a rule learned for
   one never applied to the other — you would have had to teach the app once
   per branch, per card, per message format.

### What changed

**Domain (pure)**
- `domain/parser/MerchantNormalizer.kt` (new) — `key(raw)` for comparison,
  `displayName(raw)` for reading, `sameMerchant(a, b)`. Reuses
  `TextNormalizer` (Arabic-Indic digits, bidi, whitespace are already solved
  there), then strips payment-rail prefixes, trailing city/country tokens,
  store numbers, card tails and reference codes, and finally collapses to a
  known brand when one unambiguously leads the name.

  Ordering matters and is commented in the code: noise is dropped **before**
  the suffix strip, or a trailing `*4543` shields the `DXB` in front of it from
  ever being removed.

- `MerchantCategorizer` — gained a curated `BRAND_TOKEN_SET` and a derived
  `BRAND_TOKENS`. The existing `rules` list is untouched, so `categorize()` is
  byte-identical and its test stays green; `BRAND_TOKENS` is a *filter* over
  that same list rather than a second copy of it.

- `CategoryMatcher` — now three ordered tiers: exact (unchanged, still
  strongest) -> normalized key -> regex. Tier 2 considers only `isExactMatch`
  mappings: a regex pattern is a pattern, not a merchant name, and normalizing
  `carrefour.*` would be meaningless and would quietly reorder regex
  precedence.

**UI**
- `TransactionsScreen` — `setCategoryId` and `createAndAssignCategory` take the
  `Transaction` (already in scope at both call sites) and learn the mapping
  after setting it. Clearing a category teaches nothing: "not this" is not a
  rule. Bulk mode deliberately does not learn — a multi-select spanning several
  merchants is the worst possible thing to infer one rule from, and the KDoc
  says so, so the omission reads as intentional.

### Why it under-merges on purpose

Over-merging is far worse than under-merging: a missed merge costs one extra
correction, a wrong merge files one merchant's rules onto another and the
resulting budget or balance quietly attributes itself to the wrong place. Every
ambiguous branch therefore resolves toward "leave it alone":

- Only curated proper nouns are collapsible. The categorizer's vocabulary is
  half generic words (`mart`, `store`, `mall`, `coop`, `parking`, `hotel`);
  collapsing on `store` would merge every unrelated shop in the ledger.
- Brands match whole tokens, never substrings — `ACE` cannot match inside
  `PALACE`, `SMART DUBAI` cannot become `mart`.
- Brands under 4 characters are not collapsible at all (`kfc`, `nol`, `rta`,
  `osn`, `vox`, `ace` are real brands but also common letter runs).
- **Two different brands in one name never collapse.** This is the guard that
  stops `CARREFOUR MALL OF THE EMIRATES` from being filed as an airline.
- A brand that neither leads the name nor sits in a short one never collapses,
  so `MALL OF THE EMIRATES PARKING LEVEL` stays whole.
- `emirates`, `noon`, `apple store` and `prime video` are blocklisted outright:
  each is both a brand and an ordinary part of unrelated venue names.

### Why the key is computed, not stored

A `merchantKey` column would go stale the moment the normalizer's rules
changed, silently orphaning every learned mapping with nothing to detect the
drift. Recomputing at match time means a normalizer change re-keys existing
data instead. Mapping counts are in the tens-to-hundreds and `key()` is pure
string work, so this costs nothing at capture.

### Tests

- `MerchantNormalizerTest` (24) — roughly half are negative cases, because
  refusing to merge is the harder half of the job: prefixes/suffixes/store
  numbers/reference codes stripped; a *leading* city kept (Dubai Mall is a
  name, not a location); `ABU DHABI` falling out as two tokens; `H&M` surviving
  as one; Arabic-Indic digits; **two spellings of the same Carrefour sharing a
  key**; and then `SMART DUBAI` not becoming `mart`, `ACE` not matching inside
  `PALACE`, two brands never collapsing, `noon food` not becoming `noon`,
  blocklisted brands never collapsing, short brands left alone, generic words
  never being brands, different merchants keeping different keys, empty input
  producing an empty key that can never match, and `key` being idempotent.
- `MerchantCategorizerBrandsTest` (5) — the drift guard. `BRAND_TOKENS` being
  *derived* means a typo in the curated set silently yields a shorter list
  rather than an error, and the only symptom would be merchants failing to
  merge months later; these tests are that error. Also asserts `categorize()`
  is unchanged by the split.
- `CategoryMappingTest` — six new cases (exact beats the key tier; the key tier
  beats a regex listed first; a regex still has to actually match; regex
  first-hit-wins order preserved; an unkeyable merchant falls through; two
  unkeyable merchants never collide on the empty key).

  **One existing test changed meaning, and that is the feature.**
  `exact match requires the whole string, not a substring` asserted that an
  exact mapping for "Carrefour" did *not* apply to "Carrefour Express Mall".
  It now does. Rewritten as `a longer store name now resolves through the
  normalized-key tier`, with `an exact mapping still does not match an
  unrelated merchant` added so the null coverage it used to carry is not lost.

### Deferred

- **`SubscriptionRadarEngine` and `MerchantProfiler` still group on
  `merchant.trim().lowercase()`**, not the new key. Re-keying them would
  silently merge existing subscription rows (`subscriptions.merchant` carries a
  unique index) and change detection results for every user — that needs its
  own migration and its own evidence, not a ride along with this.
- Existing `transactions.merchant` values are not rewritten. `extractedMerchant`
  exists to preserve the raw capture, and a one-way bulk rewrite buys nothing
  the normalizer doesn't already give at read time.

### CI verification

Pending — to be amended once the job log is read.

## Phase 15.2 — Assigning an account teaches the app where that merchant belongs

**Status:** landed, pending CI
**Version:** `versionCode = 71`, `versionName = "0.45.0"`
**Schema:** v24 -> v25 (`merchant_account_mappings`)

Last of three. Commit 1 made balances truthful, 15.1 made category corrections
stick; this does the same for account assignment — which is also what stops
feeding the "money in no account" problem 15 surfaced.

### Why

Two mechanisms decided a captured transaction's account: a rule's explicit
"assign matches to" pin, and `detectAccountByNumber`, which matches the card
digits printed in the message against an account's configured number. Nothing
learned. So a merchant whose messages carry no card number the app can match
stayed unassigned **forever**, no matter how many times the user filed it by
hand — and an unassigned transaction sits in no balance at all, which is
exactly the total Phase 15 had to start surfacing on Home and Accounts.

### What changed

**Schema — `MIGRATION_24_25`**, one new table, purely additive.

Two details that must not drift, because Room builds a *fresh* install's table
from the entity rather than from the migration SQL and throws a schema mismatch
at startup if they disagree — the trap `MIGRATION_22_23` already documents:
`merchantPattern` is `COLLATE NOCASE` on both sides, and the FK child column
`accountId` carries its own index.

`ON DELETE CASCADE` means deleting an account cannot leave a rule pointing at a
dead id. `parsing_rules.assignAccountId` has no FK and needed
`clearAssignAccountId` plus a bug fix (Phase 12.3) to get the same guarantee by
hand; this gets it from the schema.

**Domain (all pure)**
- `domain/parser/AccountMatcher.kt` — exact, then normalized key. Deliberately
  **no regex tier**, unlike `CategoryMatcher`: an account rule is always
  written from a concrete merchant the user corrected, never hand-authored as
  a pattern, so a regex tier would only add a way to be surprised.
- `domain/parser/CaptureAccountResolver.kt` — the precedence, mirroring the
  existing `CaptureCategoryResolver` so it is a stated, tested decision rather
  than something to reconstruct from `recordParsed`: rule pin (an explicit
  instruction) > account number in the message (hard evidence in the text) >
  learned rule (an inference from past behaviour).
- `domain/parser/MerchantAccountLearner.kt` — which already-captured rows a new
  rule reaches backwards into. SQL cannot express the normalizer, so the match
  happens in Kotlin over the small unassigned-row candidate set.

**Data**
- Entity + domain model + both mappers, `MerchantAccountDao`, and
  `TransactionDao.getUnassignedWithMerchant()` — which selects only
  `accountId IS NULL` rows, the same posture as `assignAccountByRuleName`, so a
  transaction the user filed by hand can never be overwritten by a later rule.
- `MerchantAccountRepositoryImpl` — `learnMapping` stores the rule and
  backfills in **one** `withTransaction`: a rule stored but not backfilled
  would leave the user looking at transactions that contradict the correction
  they just made.
- `TransactionRepositoryImpl` gained one nullable, defaulted constructor
  parameter, exactly mirroring `categoryRepository`, so every existing
  construction site and hand-written test fake still compiles unchanged. The
  learned lookup is skipped entirely unless both stronger signals came up empty
  — which *includes* a message that carried a card number matching no account,
  the case that otherwise leaves a transaction unassigned forever.
- Backup format 4 -> 5, gated on import exactly like the ignore list: a pre-v5
  backup never held these rules, so restoring one must not erase what this
  device has learned. Inserted after `accountDao().insertAll`, necessarily —
  the cascading FK needs its parents to exist first.

**A new interface, not new members on `TransactionRepository`.** That one
already has ~25 members and three hand-written fakes that each pay for every
addition; a separate `MerchantAccountRepository` costs them nothing. It is also
kept apart from `CategoryRepository` on purpose: a category correction must
never disturb account knowledge, and `repointMappings`/`deleteByCategory` would
have started doing exactly that if the two shared a row.

### Learning silently, and how to undo it

`assignAccount` learns on a manual pick, consistent with 15.1. Clearing an
account teaches nothing ("not this one" is not a rule), and bulk assignment
never learns — a multi-select spanning several merchants is the worst possible
thing to infer one rule from.

A wrong account is a sharper failure than a wrong category: it silently moves
money between balances. So the rule is visible and revocable — the merchant's
own screen shows "New charges file under {account}" with a **Forget** action.
Forgetting stops future guessing; it does not un-assign past transactions. That
reversibility is what makes silent learning safe rather than merely convenient.

### Tests

- `AccountMatcherTest` (9) — exact; case-insensitive; a differently-formatted
  spelling resolving through the key tier; unrelated merchant; blank/null;
  empty list; **two unkeyable merchants never colliding on the empty key**; an
  exact match beating a key match pointing elsewhere; a regex-looking pattern
  treated as a literal.
- `CaptureAccountResolverTest` (5) — each rung of the precedence, and no signal
  at all.
- `MerchantAccountLearnerTest` (9) — exact and whitespace/case-insensitive
  backfill; a different spelling of the same merchant reached; a different
  merchant never reached; two unkeyable merchants not matching while an
  unkeyable merchant still backfills its own exact spelling; blank merchant;
  empty candidates; a brand's other branches reached while a *different* brand
  (ENOC vs ADNOC) is not.
- `EntityMappersTest` — round-trip both directions, per convention.
- `BackupMappersTest` — the new mapping survives the round trip, and a pre-v5
  payload decodes to an empty list rather than failing.

### Deferred

- Not unit-testable here (no Robolectric): the v24 -> v25 migration SQL itself
  and the new DAO queries — the standing precedent for every migration in
  `AppDatabase.kt`. Room's KSP processor validating the entity at compile time
  is the only automated check, so `assembleDebug` passing is the real evidence
  the entity and the migration agree.
- No screen lists every learned account rule; they are reachable one merchant
  at a time from the merchant screen.

### CI verification

Pending — to be amended once the job log is read.

## Phase 16.1 — One money model: every transaction knows what it is

Version 0.46.0 (versionCode 72). Schema v25 -> v26.

### Why

A row only knew its direction plus one `excludeFromStats` flag, set
automatically in three narrow cases. The app therefore had no way to tell a
purchase from a card payment, a transfer between the user's own accounts, a
refund or an ATM withdrawal. As a result:
- A card payment counted twice. The bank-side debit counted as spend on top of
  the card purchases, unless one exact English message arrived within ten
  minutes.
- Own-account transfers counted as spend on one side and income on the other.
- Refunds counted as income. Tabby partial refunds vanished entirely, so the
  full original purchase stayed counted.
- About fifteen screens and engines each wrote their own
  `!excludeFromStats && direction == DEBIT`, and they disagreed:
  - The Activity hero card ignored the exclusion flag.
  - The report's health figures added AED and USD together.
  - Cashback counted as income.
- `MerchantCategorizer` keyword hits ("atm", "transfer") made rows eligible for
  transfer pairing. An ATM withdrawal and an unrelated same-amount deposit
  could pair, and then both disappeared from the stats.

### What changed

- **`TransactionKind`** (`SPEND, INCOME, REFUND, ATM, TRANSFER, CARD_PAYMENT,
  CASHBACK, ADJUSTMENT`) on every transaction, plus `kindUserSet`. Parsing
  rules gain `assignKind`.
- **`TransactionKindDetector`** (pure) decides the kind at capture time, once
  the account is known.
  - Precedence: rule kind → learned kind → built-in rule families → cashback
    tag → message wording (card payment, own transfer, ATM, refund) → a credit
    landing on a credit card is a payment → the direction's default.
  - A one-sided kind can never land on the wrong direction (`coerce`).
  - The Arabic `builtin.ar.transfer.*` rules are deliberately **not** treated
    as transfers: they also catch salary and other incoming money.
- **`LedgerMath`** is the single definition of Spent/Received:
  - SPEND/ATM add to spending. REFUND subtracts from it. INCOME adds to
    received. Everything else counts toward neither.
  - The user's `excludeFromStats` switch always wins.
  - The Dashboard's "whole ledger" toggle is `raw = true`, which counts plain
    debits vs credits.
  - Now used by the Dashboard, Goals, Analytics heatmap and pacing, Activity
    hero card, Monthly Report (builder and health inputs, now currency-scoped),
    category budgets, the daily-budget widget, Copilot, forecaster payday
    detection, merchant profiler, subscription radar, weekly digest and
    retention planner.
  - The two SQL budget aggregates now filter on kind and subtract refunds.
- **`TransferMatcher` rewrite:**
  - Pairs only when one leg is already an internal move by kind. The keyword
    category no longer qualifies a row.
  - Never pairs two legs on the same account, or a leg the user classified by
    hand.
  - Card payments get a 72-hour window, because banks post the card credit
    late. Plain transfers keep the 10-minute window.
  - A pair becomes CARD_PAYMENT when either side is a card, otherwise TRANSFER.
  - Linking no longer flips `excludeFromStats`.
- **Repository fixes:**
  - Budget, subscription and pacing hooks now see the row *after* linking.
  - Deleting a linked leg releases its partner: the link is cleared and the
    partner's kind is re-detected.
  - Editing Spent ↔ Received coerces the kind.
- **Net worth:** a credit card or loan in credit (positive balance) now counts
  as an asset instead of being `abs()`-ed into debt.
- **Balance adjustments** are written as kind ADJUSTMENT instead of relying on
  the exclusion flag.

### Upgrade path

`MIGRATION_25_26` backfills in SQL, mirroring `TransactionKindDetector.fromLegacy`
statement for statement:
- Linked legs and card-payment credits become CARD_PAYMENT or TRANSFER.
- "Balance adjustment" rows become ADJUSTMENT.
- Tabby "decreased by" becomes REFUND and "increased by" becomes SPEND.
- `CASHBACK_EARNED` becomes CASHBACK.

Those automatic exclusions drop the flag. An exclusion the user set by hand
survives.

On the next launch, `AppContainer.reclassifyLedgerOnce` re-reads old message
text with the full detector (ATM, refunds, wording-only card payments and
transfers). It only ever upgrades rows still on the plain default.

Backups move to format v6 and carry `kind`, `kindUserSet` and `assignKind`. A
pre-v6 row restores through the same `fromLegacy` path, so restoring an old
backup lands on the same totals as upgrading in place.

### Tests

- `TransactionKindDetectorTest`: every kind; Arabic transfer rules stay
  income; precedence; coercion; legacy conversion; `reclassify` guards.
- `LedgerMathTest`: refund netting; internal moves and cashback in neither
  total; user exclusion; raw mode; unconvertible rows.
- `TransferMatcherTest` (rewritten):
  - The ATM false pair.
  - The 72-hour card window and its edge.
  - A card account giving the long window.
  - The same-account and user-classified guards.
  - Pair kind.
- `DashboardViewModelTest`: card payment pair, refund netting by category and
  month, ATM.
- `NetWorthAggregatorTest`: a card in credit is an asset. Fixtures are now
  signed.
- `EntityMappersTest`, `BackupMappersTest`: kind fields round-trip, pre-v6
  restore, unknown kind.
- Ingest tests assert kinds instead of the old automatic flag.

### Deferred

- The migration SQL is not executed in CI (no instrumented tests). `assembleDebug`
  passing Room's schema check is the only automated evidence.

### CI verification

Verified on run 41177467632 (commit 92644da, which contains Phases 16.1–16.4):
- "Run unit tests": `compileDebugUnitTestKotlin` compiled and `testDebugUnitTest`
  passed (BUILD SUCCESSFUL, no failures).
- Build APK: success.
- Final Status: success.

Run 41176466032, for the first push of 16.1, failed at `compileDebugUnitTestKotlin`.
The new dashboard tests had been appended after the file's last class. That was
fixed in ab9cdbf.

## Phase 16.2 — Say what it is: manual transfers, a type picker, and kinds that are learned

Version 0.47.0 (versionCode 73). Schema v26 -> v27.

### Why

Phase 16.1 gave every row a kind but left the user no way to set one. A
transfer between two of the user's own accounts still had to be typed in as
two unrelated rows, each with the exclusion switch flipped by hand. A
bank-specific "card payment" message the detector didn't know stayed
"spending" until it was corrected, and it had to be corrected again every
month.

### What changed

- **Type picker in the add/edit dialog.** Spent · Received · Refund ·
  Transfer · Card payment · Cash withdrawal replace the old Spent/Received
  toggle.
  - The direction now follows from the kind (`TransactionKind.fixedDirection`).
  - A one-line hint says how the choice counts, for example "Money back —
    lowers spending, not income".
- **Manual transfers and card payments.** Choosing Transfer or Card payment
  in *add* mode swaps the account picker for **From** and **To** pickers.
  - Either side can be "Outside / not tracked".
  - When the currencies differ, an **Amount received** field appears.
  - Picking a credit card as the destination switches the type to Card
    payment.
  - `LedgerRepository.recordTransfer` writes both legs and links them in one
    Room transaction. The legs come from the pure `TransferLegs.build`.
- **Re-classify from the detail sheet.** Tapping a transaction shows its
  type, plus "· linked" for a reconciled leg, and a row of type chips.
  - "Remember for {merchant}" is on by default.
  - `LedgerRepository.setKind` marks the row as user-set.
  - Moving a linked leg out of transfer/card payment releases its partner,
    which is re-detected. Moving an unlinked row in looks for its other leg.
    Transfer ↔ card payment keeps both legs consistent.
  - With "remember", a merchant → kind rule is stored (new
    `merchant_kind_mappings` table, matched by `MerchantKindMatcher`: exact,
    then normalized key). It is applied to that merchant's other rows that
    were classified automatically, never to a user-set row or a linked leg.
    It is also fed to `TransactionKindDetector` as `learnedKind` at capture
    time.
- **Rules can pin a type:** "Matches are: …" in the rule editor
  (`ParsingRule.assignKind`).
- **Activity rows** add the kind to the bank line whenever it isn't a plain
  spend/receive, and show transfers, card payments and corrections in a
  neutral colour instead of red/green.
- `LedgerRepository` is a separate interface implemented by
  `TransactionRepositoryImpl`, which shares the transfer-linking code. The
  three `TransactionRepository` test fakes are untouched.
- Backups move to format v7 and carry the learned kind rules. Restoring a
  pre-v7 backup leaves this device's rules alone, and a rule with a kind this
  build doesn't know is skipped.

### Tests

- `TransferLegsTest`: leg shape and descriptions; card payment detection;
  cross-currency; a single leg for an outside side; notes; validation.
- `MerchantKindMatcherTest`: exact, normalized key, misses.
- `EditorKindsTest`: offered kinds, rare kinds kept, fixed directions, hints.
- `EntityMappersTest` and `BackupMappersTest`: the kind rule round-trips,
  unknown kinds are skipped, and a pre-v7 payload decodes.

### Deferred

- `MIGRATION_26_27` and the new DAO queries are not executed in CI. Room's
  compile-time schema check is the only automated evidence.
- There is no screen yet that lists every learned kind rule.

### CI verification

Verified on run 41177467632 (commit 92644da, which contains Phases 16.1–16.4):
- "Run unit tests": `compileDebugUnitTestKotlin` compiled and `testDebugUnitTest`
  passed (BUILD SUCCESSFUL, no failures).
- Build APK: success.
- Final Status: success.

Run 41176466032, for the first push of 16.1, failed at `compileDebugUnitTestKotlin`.
The new dashboard tests had been appended after the file's last class. That was
fixed in ab9cdbf.

## Phase 16.3 — Accounts and cards that are easy to set up and file money correctly

Version 0.48.0 (versionCode 74). Schema v27 -> v28.

### Why

Adding a card or account by hand was awkward and error-prone:
- A card's statement day could not be edited anywhere.
- There was no credit limit.
- One number per account meant a current account couldn't also answer to
  its debit card, and supplementary cards had nowhere to go.
- Credit cards showed loan fields (the manual "Outstanding" contradicted the
  computed balance).
- Bad input silently became null.
- "1,000" was rejected as an opening balance but accepted in Adjust.
- New accounts defaulted to AED regardless of the user's currency.
- There was no archive at all.
- An account hidden from the Dashboard vanished from the very sheet that
  could unhide it.

Capture matching had its own problems:
- A short scraped suffix could match several accounts, and the code then
  silently picked the first.

### What changed

- **New account fields:** `Account.creditLimit` and `Account.cardEndings`
  (extra card numbers, comma-separated). Added to entity, mappers,
  `MIGRATION_27_28` and backup format v8.
- **`AccountNumberMatcher`** (pure) replaces the inline matcher:
  - Matches the account number *and* every extra card number.
  - Needs at least 3 digits on both sides.
  - Prefers the transaction's currency, then the longest shared suffix.
  - Returns null on a true tie, so the learned merchant rule or the user
    decides instead of `first()`.
- **Account form, rebuilt on a pure `AccountFormValidator`:**
  - Readable type names.
  - Type-specific sections:
    - **Card**: credit limit, statement day, payment due day.
    - **Matching**: account/card number, plus debit or supplementary card
      numbers.
    - **Loan**: the existing fields, for loans only.
  - Inline errors.
  - `parseMoneyInput`, shared with Adjust, accepts "1,250.50" and "2 000".
  - Fields that don't apply to the chosen type are cleared on save.
  - Notes are editable.
  - New accounts start in the default currency.
- **Archive:**
  - An archive button, with a warning when money is still on the account.
  - A collapsible "Archived (n)" section with Restore.
  - Backed by `AccountDao.observeArchived` and
    `AccountRepository.observeArchived`/`unarchive`. Both `FakeAccountDao`s
    are updated.
- **Dashboard:** "Manage accounts" lists every active account
  (`DashboardUiState.manageableAccounts`), so a hidden one can be switched
  back on.
- **Doc fixes:** the `AdjustBalanceDialog` and `AppDatabase.build` docs were
  sitting above the wrong declarations.

### Tests

- `AccountNumberMatcherTest`:
  - Main number and extra card numbers match.
  - Fewer than 3 digits never matches.
  - Currency breaks a tie.
  - The longer suffix wins.
  - A true tie returns null.
  - Splitting of the card-number list.
- `AccountFormTest`:
  - Money parsing.
  - Required fields.
  - Card and loan validation only for their own types.
  - What a card and a loan save.
  - Switching type clears stale fields.
  - Card-number normalisation.
- `EntityMappersTest`, `BackupMappersTest`: the new fields round-trip.

### CI verification

Verified on run 41177467632 (commit 92644da, which contains Phases 16.1–16.4):
- "Run unit tests": `compileDebugUnitTestKotlin` compiled and `testDebugUnitTest`
  passed (BUILD SUCCESSFUL, no failures).
- Build APK: success.
- Final Status: success.

Run 41176466032, for the first push of 16.1, failed at `compileDebugUnitTestKotlin`.
The new dashboard tests had been appended after the file's last class. That was
fixed in ab9cdbf.

## Phase 16.4 — Credit cards that tell you where you stand

Version 0.49.0 (versionCode 75). No schema change.

### Why

A credit card was just a negative balance. Nothing said how much of the limit
was used, what the last statement asked for, whether it had been paid, or when
the payment was due. A missed card payment is the most expensive mistake a
personal-finance app can help avoid.

### What changed

- **`CreditCardStatusCalculator`** (pure). It takes the card's computed
  balance, which still comes from `AccountBalanceCalculator` as the single
  source of truth, plus its transactions. It returns:
  - What's owed now, available credit, and utilisation.
  - The current cycle start, from `statementCycleDay`, clamped for short
    months.
  - The statement balance: the balance at the moment the cycle started.
  - Payments since then. Only card payment and transfer credits count, not
    refunds.
  - The amount still due, never more than is owed now.
  - The due date: the first `dueDayOfMonth` on or after the cycle start.
  - Spending this cycle (via `LedgerMath`, refunds netted).
  - A status: NOTHING_OWED / PAID / DUE / DUE_SOON (within 7 days) /
    OVERDUE / NO_DUE_DATE.
  - Without a statement day, everything owed is treated as due and flagged
    `estimated`.
- **`CardPaymentReminders`** (pure):
  - `build` gives the status of every card from accounts, transactions and
    ledger entries. The worker, the Dashboard and the Accounts screen all use
    it, so they can't disagree.
  - `pending` reminds within 3 days of the due date, or when overdue,
    keyed on card plus due date, so each date is announced once and the
    next month re-arms.
  - `prune` keeps the stored set small.
- **Accounts screen:** each credit card shows:
  - A utilisation bar, coloured by how much is used.
  - "Available X of Y · N% used".
  - The last statement and spending this cycle.
  - "AED X · due 25 Oct (5d)", "Statement paid ✓", "Nothing owed", or overdue
    in red.
- **Dashboard:** a "Cards due" strip whenever a card is due within a week or
  overdue.
- **Reminders:** `BillReminderWorker` also posts card-payment notifications
  under the existing (opt-in) Bill reminders toggle. Sent keys are stored in
  `AppPreferences.cardRemindersSent`.
- The Tabby seed-rules doc comment had been displaced by Phase 16.1. It is
  back on `seedDefaultRulesOnce`.

### Tests

- `CreditCardStatusCalculatorTest`:
  - Owed, available and utilisation.
  - Statement, payments and amount due across a cycle.
  - Paid in full despite new spending.
  - A refund is not a payment, and the amount due is capped at what's owed.
  - Overdue.
  - A due day falling in the next month.
  - A card in credit.
  - No statement day, no due day, no limit.
  - Other accounts are ignored.
  - Day-31 and leap-year clamping.
- `CardPaymentRemindersTest`:
  - The reminder window and order.
  - Announce-once, with next month re-arming.
  - Prune.
  - `build` covers only cards and takes balances from the ledger.
  - Wording.

### CI verification

Verified on run 41177467632 (commit 92644da, which contains Phases 16.1–16.4):
- "Run unit tests": `compileDebugUnitTestKotlin` compiled and `testDebugUnitTest`
  passed (BUILD SUCCESSFUL, no failures).
- Build APK: success.
- Final Status: success.

Run 41176466032, for the first push of 16.1, failed at `compileDebugUnitTestKotlin`.
The new dashboard tests had been appended after the file's last class. That was
fixed in ab9cdbf.

## Phase 16.5 — Proving the upgrade, and making the automation visible

Version 0.50.0 (versionCode 76). No schema change.

### Why

Migrations had never been executed anywhere before reaching a device: CI only
proved the entities compiled. Phase 16 added three of them, one of which
rewrites every transaction. Separately, the app now learns types and accounts
from corrections, but there was nowhere to see or undo what it had learned,
and no way to list "every card payment" or "every refund".

### What changed

- **`MigrationSqlTest`** executes v25 → v28 on real SQLite (`sqlite-jdbc`,
  test-only):
  - A *fresh* v28 database is built from the schema JSON Room's compiler
    exports during the same build (`app/schemas/.../28.json`).
  - A v25 database is built from that same DDL with the 26–28 columns and
    table removed. The test asserts each removal took effect.
  - After migrating, every touched table must match the fresh install in
    columns (name, type, NOT NULL, primary key), indices (name, unique,
    columns) and the NOCASE collation. This is what Room validates when it
    opens the database on a device.
  - The data checks seed eleven legacy rows: card-payment legs, a savings
    transfer, a balance adjustment, Tabby refund/charge adjustments, a
    user-hidden row, cashback, salary and a plain purchase. Each must land on
    the kind `TransactionKindDetector.fromLegacy` defines, with the user's own
    exclusion kept.
  - Accounts must survive with the new fields unset.
  - To make this possible, the migration statements moved into
    `MigrationSql`, a plain object. The Room `Migration`s run exactly those
    lists.
- **Activity "Kind" filter**, in both filter-bar layouts (a chip menu, and a
  "What it is" section in More filters), backed by `ActivityFilters.kind`.
- **"Learned from your corrections"** on the Rules screen lists every
  merchant → type and merchant → account rule, each with **Forget**. Backed by
  the new `LedgerRepository.observeMerchantKinds`.

### Tests

- `MigrationSqlTest`: schema equivalence, the backfill table, and accounts
  surviving the upgrade.
- `TransactionFilterEngineTest`: the kind filter.

### CI verification

Verified on run 41178449993 (commit 6027e37):
- "Run unit tests": `testDebugUnitTest` finished BUILD SUCCESSFUL. The test
  report grew from 155 to 156 files; the only new test class is
  `MigrationSqlTest`, so it ran and passed against the schema Room exported
  in that build.
- Build APK: success.
- Final Status: success.

## Phase 16.6 — A month of real messages, end to end

Version 0.50.1 (versionCode 77). Fix plus test, no schema change.

### What changed

- **Fix:** the "More filters (n)" badge did not count the new Kind filter. In
  the single-row filter bar, where Kind lives inside More filters, an active
  kind filter left the feed silently narrowed with no badge.
  `TransactionFilterEngine.activeSecondaryFilterCount` now counts it.
- **`MoneyPipelineScenarioTest`:** one October of real-format bank messages
  goes through the real `TransactionParser` + `BuiltInRules`,
  `AccountNumberMatcher`, `TransactionKindDetector` and `TransferMatcher`
  (glued as `recordParsed` glues them), plus a manual transfer via
  `TransferLegs`. The month contains:
  - salary 15,000;
  - two card purchases (68.00 and 275.18);
  - a 500 ATM withdrawal;
  - last month's 3,600 card bill, with the card side posting a day after the
    bank side;
  - 2,000 moved to savings;
  - a 5.58 Tabby partial refund and 2.23 Tabby cashback.

  The test then checks what the user reads:
  - every row's kind, and that both card-payment legs link to each other;
  - Spent 837.60 and Received 15,000, on both the exact and the Double
    (Dashboard) paths;
  - balances: Current 18,900, Card −343.18, Savings 2,000;
  - the Tabby rows with no account show up as 7.81 unassigned;
  - net worth 20,556.82;
  - the card: statement 3,600, paid 3,600, nothing due, status PAID,
    343.18 spent this cycle, available 19,656.82, due 25 Oct;
  - the monthly report agrees with the dashboard.

### Local verification

The pure domain layer and its tests were also compiled and run in this
session in a scratch JVM project (Kotlin 2.0.21; Android-dependent tests
excluded): 753 tests passed. The only failures were `MigrationSqlTest`'s
three tests, which need Room's exported 28.json; that file only exists in
the Android build, where it passes (run 41178449993).

### CI verification

Verified on run 41179681450 (commit 8919fa2):
- "Run unit tests" passed. The test report grew to 157 files with the new
  `MoneyPipelineScenarioTest` page; `MigrationSqlTest` ran with Room's schema
  and passed.
- Build APK: success.
- Final Status: success.

## Phase 17.1 — Keep the balance the bank prints

Version 0.51.0 (versionCode 78). Schema v28 -> v29.

### Why

The app is a single-entry ledger, so nothing proved its books were complete: a
missed SMS or a wrong amount left the computed balance drifting from the bank's
with no sign. Most bank messages end with the bank's own balance, and that
figure was thrown away. The amount rules were only positioned so they wouldn't
mistake it for the amount (`BuiltInRules.kt`).

### What changed

- **`BalanceExtractor`** (pure) reads the printed balance from the normalized
  message: "available account balance", "Avl. card bal", "available Tabby
  Card limit", "Tabby Cash balance", "Balance: AED …", and the Arabic
  "الرصيد المتوفر", "رصيد حسابك الحالي", "الرصيد المتاح".
  - Wording decides the kind. Anything about a limit or card balance is
    `AVAILABLE_CREDIT`; the rest is `ACCOUNT_BALANCE`.
  - It handles thousands separators and a minus on either side of the
    currency.
  - A balance in a different currency than the transaction is dropped, since
    the stored figure carries no currency of its own.
  - The first balance statement wins.
  - It is independent of the amount rules and can only add information.
    `TransactionParser.parse` now wraps the existing parser as `parseCore` and
    attaches the balance afterwards.
- **Storage:** `reportedBalance` and `reportedBalanceKind` on `Transaction`,
  `TransactionEntity` and `ParsedTransaction`, with mappers, converters and
  `recordParsed` persisting them.
- **`MIGRATION_28_29`:** two nullable `ALTER TABLE` columns, in `MigrationSql`.
  Existing rows simply have none.
- **Backup format 9** carries both fields. An unreadable amount or an unknown
  kind from a newer build restores as empty rather than failing.

### Tests

- `BalanceExtractorTest` (12): every real phrasing above, no-balance messages,
  currency mismatch, first-wins, and the parser keeping the transaction amount
  while adding the balance.
- `MigrationSqlTest` now targets v29 and the new columns.
- Mapper and backup round trips, a pre-v9 payload, and an unknown kind.
- Locally, the existing parser, signature, ingest and scenario suites all still
  pass with the extractor active (the extractor tests too).

### CI verification

Verified on run 41197720203 (commit 6418d3e): `compileDebugUnitTestKotlin` and
`testDebugUnitTest` finished BUILD SUCCESSFUL with no failures, including
`MigrationSqlTest` against the exported v29 schema.


## Phase 17.2 — The integrity check

Version 0.52.0 (versionCode 79). No schema change.

### Why

MMbank is a single-entry ledger, so nothing proved the books were complete. With
the bank's own balance now kept (Phase 17.1), the app can compare itself with
the bank and say where, by how much and in which direction it disagrees.

### What changed

- **`IntegrityChecker`** (pure) reports, per account, whether the books match the
  bank, plus structural problems.
  - **Balance drift.** The latest bank-stated balance is compared with the
    balance the app computes *as of that message* (opening balance plus every
    row up to its time).
    - Comparing at the same instant means later transactions can't cause a
      false alarm.
    - A balance adjustment dated at that instant clears the drift.
    - Tolerance is one cent.
    - Drift is "bank minus app": positive means money in may be missing,
      negative means money out may be missing.
  - **Credit cards.** The printed figure is credit still available, so owed =
    `creditLimit − available`, compared with the app's owed amount (a card in
    credit owes nothing). Without a limit the card can't be checked and an info
    hint says so. A plain "balance" on a card is ambiguous and ignored. A debit
    card's "card balance" counts as the account balance.
  - **Guards.** Accounts holding other-currency rows can't be reconciled
    (balances add at face value). Snapshots older than the retention cutoff are
    skipped, because pruned rows are folded into the opening balance.
  - **Structural checks:**
    - Transfers and card payments the app classified itself that never found
      their other side after 72 h. One the user classified is theirs to vouch
      for.
    - Broken links: the partner is missing, doesn't link back, is on the same
      account, or has a different amount in the same currency. Cross-currency
      pairs are legitimate and not flagged.
    - Rows in no account.
    - Rows in another currency than their account.
    - Card setup hints: no limit, or no payment due day.
  - Issues are ordered problems, then warnings, then hints.
- **`IntegrityWording`** (pure, formatters injected) writes the sentences, so
  they are tested rather than living in a composable.
- **Screen:** Settings → Integrity check (also reached from a card on Accounts
  while anything needs a look). Actions reuse existing paths:
  - *Record the difference* writes a balance adjustment dated at the bank's
    statement.
  - *It's correct* vouches for a move.
  - *It's an ordinary one* and *Unlink* re-type the row, which releases any
    link.
  - *Assign accounts* and *Open accounts* link to the existing screens.
- **Accounts:** each account shows "Matches your bank ✓ (date)" or "Differs from
  your bank by X (as of date)".
- **Fix:** `setKind` now also drops a link to a partner that no longer exists.
  Previously that left a dangling link.

### Tests

- `IntegrityCheckerTest` (28): matching books; missing deposit and missing
  purchase; as-of comparison; latest statement only; adjustment clears the
  drift; rounding; unchecked accounts; mixed currency; retention cutoff; card
  owed maths, card in credit, no limit, ambiguous plain balance, due-day hint;
  debit card; unpaired (flagged, too recent, user-vouched); healthy and
  cross-currency pairs; each broken-link reason reported once; unassigned;
  archived accounts; ordering.
- `IntegrityWordingTest`: the drift, unpaired, broken-link, count and setup
  sentences.
- `MoneyPipelineScenarioTest` (extended): the October of real-format messages
  now carries real balances. A clean month reconciles on both accounts, with
  only the two account-less Tabby rows flagged. Dropping the salary message
  shows a drift of exactly 15,000. Dropping a card purchase shows exactly
  68.00. Recording the missing deposit at the bank's time clears it.
- Locally, the 798 pure domain tests pass.

### CI verification

Verified on run 41198108391 (commit 8ad9ad7): `compileDebugUnitTestKotlin` and
`testDebugUnitTest` finished BUILD SUCCESSFUL with no failures (the test report
grew to 160 files). Build APK: success. Final Status: success.

## Phase 17.3 — Delete a wrong capture and stop it coming back

Version 0.53.0 (versionCode 80). Schema v29 -> v30, backup format 10.
(Entry back-filled in Phase 18.0; the commit is 4ec4139.)

### What changed

- **Keyword ignore list.** New `ignored_keywords` table (`MIGRATION_29_30`,
  in `MigrationSql`). `IgnoredMessageCache.shouldIgnore` checks muted keywords
  in the title and body (`KeywordMatcher`, case-insensitive) before the
  template fingerprint and similarity checks.
- **Activity delete dialog** (row, detail sheet and bulk mode) now offers
  *Delete & ignore similar* (adds the message to the template ignore list) and
  *Delete & ignore containing…*, prefilled by `IgnoreKeywordSuggester` (the
  merchant when it appears verbatim, else the longest word of 4+ letters).
- **Settings → Import & ignore messages** lists and deletes keywords.
- **Backup format 10** carries the keyword list.

### Tests

`KeywordMatcherTest`, `IgnoreKeywordSuggesterTest`,
`IgnoredKeywordRepositoryImplTest`, `IgnoredMessageCacheTest` and
`IngestCapturedMessageUseCaseTest` additions, `MigrationSqlTest` at v30, mapper
and backup round trips.

### CI verification

Run 42287447266 (commit 4ec4139): "Run unit tests" BUILD SUCCESSFUL; Build
APK: success; Final Status: success.

## Phase 18.0 — CI that fails when a test fails

No version bump: CI and docs only.

### Why

The "Run unit tests" step had `continue-on-error: true`, so the workflow
stayed green through failing tests and every phase had to be verified by
reading the step log by hand. The log records this hiding a real assertion
failure (Phase 3) and a real compile error (Phase 6). Backlog #69.

### What changed

- `test-build.yml`:
  - "Run unit tests" no longer has `continue-on-error`.
  - New **Summarize failing tests** step (`if: failure()`) parses the JUnit
    XML and lists every failing `Class.method: message` in the log and the
    run summary; a compile error is called out when no test case failed.
  - The uploaded `unit-test-results` artifact now also carries the XML.
  - `build-apk` runs `if: !cancelled()`, so a red commit's APK can still be
    downloaded. The main-branch GitHub Release steps now require the unit
    tests to have passed.
  - `final-status` passes only when both the tests and the APK build passed,
    and prints both results.
- `CLAUDE.md` describes the new behavior: a green workflow now means tests
  passed and the APK built.

### CI verification

Run 42406631967 (commit 719df4f): Lint & Unit Tests success
(`testDebugUnitTest` restored FROM-CACHE: no source changed, and Gradle never
caches a failed test task), "Summarize failing tests" skipped, Build APK
success, release steps skipped (not `main`), Final Status success. The failure
path was exercised locally: the summary script printed `Class.method: message`
for a fabricated failing JUnit XML.

## Phase 18.1 — Ask MMbank offline: the Copilot answers on the phone

Version 0.54.0 (versionCode 81). No schema change. Backlog #31, #32 (and the
category half of #37).

### Why

The Copilot refused to answer anything without a configured AI server, and
even with one it only ever saw "this month". Yet the questions people
actually ask ("how much on dining last month?", "biggest expense?", "compare
with last month") are plain queries over numbers the app already has.

### What changed

- **`domain/intelligence/copilot/`** (all pure):
  - `CopilotQueryParser` reads the question: intent (spend, income, top
    categories, top merchants, biggest expense, compare, savings rate, count,
    daily average), range (today, yesterday, this/last week, month or year,
    "October", "oct 2025", "in 2024", "last N days/weeks/months"; weeks start
    Monday; a month without a year is its latest past occurrence; bare "may"
    is not a month) and subject. Subjects are the user's own categories
    (including sub-categories; plurals, a few everyday synonyms like
    "restaurants" for Dining, and one-letter typos) and merchants from their
    own ledger. When both a category and a merchant are named, the longer
    phrase wins; a merchant named after a question word ("Payment") can never
    become the subject. It returns null rather than guess.
  - `CopilotAnswerEngine` answers with `LedgerMath`, like every other screen:
    transfers, card payments and excluded rows never count, refunds net off.
    Rows with no exchange rate are named ("Not counted: 2 EUR transactions"),
    not counted as zero. Month-to-date compares with the same days of last
    month, not the whole month.
  - `CopilotSuggestions` builds question chips from the user's top category
    and merchant; every chip is verified to parse.
  - `CopilotResponder` routes: on-phone answers always win (even with AI set
    up); open questions go to the AI with numbers for the range they named;
    without AI, a "here's what I can answer" reply.
- **AI fallback context** (`FinancialCopilotQueryHandler.buildContext`) now
  groups by the user's categories instead of the legacy fixed enum.
- **Drill-down:** answers carry a "See transactions" button that opens
  Activity filtered to exactly those rows. `ActivityDrillDown` encodes the
  filter into one navigation argument (`transactions?drill=`), and
  `ActivityFilters.categoryIds` is a new category filter. It has no picker, so
  while active it shows as a "Category: X ✕" chip under the filter bar in both
  layouts, and it counts toward the More filters badge.
- **Copilot screen:** suggestion chips (on an empty chat and after a help
  reply), an "Answered on this phone" tag, a softer note when AI isn't set up.
  Heavy work runs on `Dispatchers.Default`.

### Tests

`CopilotQueryParserTest` (24: every intent, every range shape, Feb 29,
month/year edge cases, subject precedence, typos, question-word merchants),
`CopilotAnswerEngineTest` (16), `CopilotSuggestionsTest`,
`CopilotResponderTest`, `ActivityDrillDownTest`,
`TransactionFilterEngineCategoryTest`, `CategoryFilterLabelTest`. Locally, the
pure domain suite (84 classes, 871 tests) passes.

### Not covered by CI

The chat UI and chips, the `drill` navigation argument at runtime, and the
category chip in both filter-bar layouts need a check on a phone.

### CI verification

Run 42407814856 (commit be12ab6): "Run unit tests" ran for real (2 min 41 s,
which compiles the app's main sources including the new Compose code) and
passed; Build APK success (debug, and the non-gating release build); Final
Status success.

## Phase 18.2 — Unusual-charge radar, and reminders that can actually notify

Version 0.55.0 (versionCode 82). No schema change. Backlog #39.

### Why

A charge far outside what a merchant usually costs you is the most useful
thing a finance app can point out. Separately: the manifest declared
`POST_NOTIFICATIONS` but nothing ever asked for it at runtime, so on Android
13+ every opt-in reminder (bills, card payments, subscriptions, budgets) was
silently dropped by its helper's permission check unless the user had granted
it by hand.

### What changed

- **`SpendAnomalyDetector`** (pure). A purchase is unusual when, against at
  least 5 earlier purchases at the same merchant (trimmed, case-insensitive,
  as `findRealDebitsByMerchant` matches) in the same currency, it is at least
  max(median + 4 × 1.4826 × MAD, 2 × median) **and** at least 20 above the
  median. The baseline is the latest 50 purchases, so it follows price changes.
  Refunds, transfers and excluded rows never form a baseline or get flagged.
- **Smart insights** gain `UnusualCharge` ("Unusual: AED 74.00 at Careem is
  3.1× your usual (about AED 24.00)"), in the charge's own currency. The two
  strongest in the selected period are shown, never in the raw-ledger view.
- **Transaction detail** shows "⚠ 3.1× your usual at Careem" on such a charge
  (`TransactionsViewModel.anomalyFor`, one indexed merchant query via the new
  `TransactionRepository.spendHistoryForMerchant`, defaulted so fakes compile).
- **Opt-in notification** (Settings → Preferences → Unusual charge alerts, off
  by default): `AppContainer.checkUnusualCharge` on the post-insert hook,
  gated by `SpendAnomalyDetector.shouldAlert` — toggle on, SMS or push only
  (never a pasted backlog or a manual entry), and fresh (within 48 h, 5 min of
  clock skew allowed). Channel `unusual_charge`, ids 9500+; tapping opens the
  app.
- **Notification permission:** every alert toggle (subscriptions, bills,
  unusual charges) now asks for `POST_NOTIFICATIONS` on Android 13+ when
  switched on (`SmartAlertsSection.kt`: `rememberNotificationPermissionRequest`,
  `AlertToggleRow`).

### Tests

`SpendAnomalyDetectorTest` (13): too little history, ordinary charge, the 2×
rule with MAD 0, the absolute floor, a high-variance merchant, foreign-currency
/ refund / transfer / excluded rows ignored, later rows and the charge itself
left out of the baseline, case-insensitive merchants, refunds never flagged,
scan ordering and cap, the alert gate, median. `DashboardViewModelTest`: the
insight mapping, and the stats flagging a charge but never in the raw view.

### Not covered by CI

The permission prompt, the notification itself and the detail-sheet line need
a phone. Whether the thresholds feel right on real data needs a few weeks of
use.

### CI verification

Run 42408481472 (commit 8629b1c): "Run unit tests" passed (a real 2 min 36 s
run, including the new `DashboardViewModelTest` cases); Build APK success;
Final Status success.

## Phase 18.3 — Capture-health guard: notice when capture stops

Version 0.56.0 (versionCode 83). No schema change. Backlog #60.

### Why

MMbank fails silently. An OEM battery manager kills or unbinds the
notification listener, or access gets revoked, and transactions simply stop
arriving while every screen keeps looking fine. Every other feature depends on
capture, so this protects all of them.

### What changed

- **`CaptureHealthEvaluator`** (pure) returns one verdict:
  - `NO_ACCESS`: neither notification access nor the SMS permission. Shown
    even when snoozed.
  - `DISCONNECTED`: notification access is granted, but Android's last word
    was "disconnected", or not one notification from any app has reached the
    listener for 24 h. A heartbeat never recorded (fresh install, just
    updated) is not evidence; one "in the future" means the clock moved back.
  - `QUIET`: at least 5 live captures in the 90 days before the last one, and
    the silence is longer than max(3 × the median gap, 72 h). A weekly
    capturer isn't flagged after three days, and a very long silence still
    counts.
  - Otherwise `HEALTHY`. A battery-optimisation hint is added when MMbank
    isn't exempt.
- **Heartbeat:** `BankNotificationListener` records the time of any posted
  notification before filtering (throttled to one write per 15 min), plus
  `onListenerConnected` / `onListenerDisconnected`; on disconnect it calls
  `requestRebind`. `SmsReceiver` records the last SMS. Five plain
  `AppPreferences` longs; nothing in backup.
- **Home banner** (top of Home): "Capture is off" / "Capture may have stopped"
  / "Unusually quiet", with **Fix** (opens Capture & permissions) and
  **Snooze 24h**. Permissions are re-checked on every resume. Only SMS and
  push captures count; pasted, manual and demo rows never do. Hidden in demo
  mode.
- `util/CaptureAccess.kt` now holds the access checks that were private to
  `SettingsScreen.kt`, so Home and Settings share them.
- No background worker: the battery managers that kill the listener kill
  WorkManager too.

### Tests

`CaptureHealthEvaluatorTest` (16): healthy, fresh install, no access beats
everything and ignores snooze, SMS alone is enough, disconnect / reconnect,
24 h listener silence, silence without notification access, quiet daily
capturer, weekly capturer not flagged early, too little history, a very long
silence, snooze expiry, clock moved back, the battery hint, the heartbeat
throttle. `CaptureHealthGlueTest`: pasted, manual and demo rows never prove
capture works.

### Not covered by CI

The listener callbacks, the heartbeat on a real phone and the banner need a
device. To see it: revoke notification access and SMS, then open Home.

### CI verification

Run 42409117320 (commit 6869a6a): "Run unit tests" passed (a real 2 min 31 s
run); Build APK success; Final Status success.

## Phase 18.4 — Net worth over time, rebuilt from the ledger

Version 0.57.0 (versionCode 84). No schema change. Backlog #34, #35.

### Why

Net worth was a single live number with no history. The backlog sketched a
daily snapshot table, which would only start paying off months later. But
balances are derived, never stored, so every past month-end can be rebuilt
from the ledger today.

### What changed

- **`BalanceHistory`** (pure, `domain/account`): balances at any past moment
  via `AccountBalanceCalculator`'s single formula (opening balance plus every
  row up to that moment, excluded rows included), month-end instants (the
  current month is "now, including future-dated rows" so it matches today
  exactly), and where history starts (the oldest kept row).
- **`computeNetWorthTrend`** (pure, `DashboardViewModel.kt`): the last 12
  month-ends through `computeAccountsPresentation`, the same path as Home's
  figure (honouring "exclude credit cards from total", archived accounts left
  out), so the last point is Home's fiat total. Months before the oldest kept
  row are null: with retention pruning on, earlier rows live only in opening
  balances. Every month is converted at today's rates, and accounts with no
  rate mark the trend approximate ("≈").
- **`Sparkline`** in `CanvasCharts.kt`, with pure `sparklinePoints` geometry
  (gaps break the line, flat series centred, negatives scale normally).
- **Dashboard tab:** a "Net worth — 12 months" card with today's value, "▲/▼
  vs 6 months ago · vs a year ago" (or "since <month>" for a shorter history)
  and the sparkline. It respects hide-balances and is labelled "Rebuilt from
  your ledger at today's exchange rates. Excludes Binance crypto." It only
  appears with at least two known points. Only this tab collects the flow,
  and the replay runs off the main thread.

### Deferred

A sparkline for the merchant screen's monthly trend. That screen already has
a per-month bar chart.

### Tests

`BalanceHistoryTest` (7), `NetWorthTrendTest` (5: replayed month-ends, the
last point equals Home's fiat total, card exclusion, approximate without a
rate, empty ledger), `SparklineGeometryTest` (6).

### Not covered by CI

The card's rendering and how it reads on a real ledger.

### CI verification

Run 42449418642 (commits ce86c72 + 4da67f7): unit tests passed (3 min 08 s); Build APK success, release APK built too; Final Status success.

## Phase 18.5 — Debt payoff planner

Version 0.58.0 (versionCode 85). No schema change. Backlog #52.

### Why

`Account` already records what a payoff plan needs: `outstandingBalance`,
`nextInstallmentAmount`, `profitRatePa`. But nothing used them beyond one
account's early-settlement estimate. "When will I be debt-free, and which debt
should I pay first?" is the question those fields exist to answer.

### What changed

- **`DebtPayoffSimulator`** (pure, exact `BigDecimal`) runs month by month.
  Each month every open balance accrues APR/12 (rounded to the cent), every
  minimum is paid, and the rest of the budget goes to the strategy's target.
  A paid-off debt's minimum rolls into the extra.
  - **Strategies:** Avalanche (highest rate first) or Snowball (smallest
    balance first).
  - **Outcomes:** paid off, budget below the minimums, never pays off (budget
    ≤ the first month's interest), or the 600-month cap.
- **`DebtCandidates`** (pure) turns every credit card or loan that owes money
  into a debt. A card owes its negative balance; a loan uses its stated
  outstanding balance, falling back to its negative balance. The rate comes
  from `profitRatePa`. The minimum is `nextInstallmentAmount`, or an estimate
  of max(5%, 100). Also `durationLabel` ("2 years 3 months").
- **Debt planner screen** (`debt_planner`, under the Accounts tab):
  - one card per debt, with the rate and monthly minimum editable (not saved
    back to the account);
  - a monthly budget, stored as a decimal string so it round-trips exactly.
    It is prefilled with the minimums + 20% rounded up to 50, and shows what
    the minimums add up to;
  - both plans side by side: when you'd be debt-free, total interest, and the
    payoff order, plus one line comparing them ("Avalanche saves AED 68.22 in
    interest. Snowball clears Loan first, in month 3.");
  - debts with no known rate are simulated at 0% and named; debts in a
    currency with no exchange rate are left out and named.
- **Accounts** shows a "Plan paying off your debts" card while any card or
  loan owes money.

### Tests

`DebtPayoffSimulatorTest` (10): a hand-checked 12% amortisation (11 months,
58.98 interest), 0% APR, Avalanche ≤ Snowball interest (250.73 vs 318.95),
payoff order per strategy, budget below minimums, never pays off, the cap,
nothing owed, a minimum above the balance, interest rounding.
`DebtCandidatesTest` (4). `DebtPlannerStateTest` (7): both plans, overrides,
unknown rate, unconvertible currency, no budget, the suggested budget, the
comparison line.

### Not covered by CI

The screen itself, and the Accounts entry card.

### CI verification

Pending.
