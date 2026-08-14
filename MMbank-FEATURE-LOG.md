# MMbank — Feature Log

Running record of feature work landed after the Phases 0-6 upgrade plan (see
`MMbank-HANDOVER.md` for that earlier state). One entry per phase, written in
the same commit as the code and amended once CI reports the real result.

Why this file exists: CI proves compilation only, several sessions have worked
this branch concurrently, and chat scrollback is not a durable record. Anything
worth knowing later belongs here and in git history.

---

## Phase 1 — Per-category budgets & cap alerts

**Status:** code complete, CI pending
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

_Pending — to be filled in with the run ID, `assembleDebug` result, and the
actual unit-test line read from the `Lint & Unit Tests` job log (not the
checkmark, which is masked green by `continue-on-error`)._
