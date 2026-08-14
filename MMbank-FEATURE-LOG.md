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

**Status:** code complete, CI pending
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

_Pending._
