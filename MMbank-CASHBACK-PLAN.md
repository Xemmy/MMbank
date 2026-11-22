# MMbank — Cashback screen: make it usable (plan)

> Written 2026-11-22 against v0.140.7 (versionCode 208), branch
> `claude/mmbank-local-ai-parsing-4cfi14`. **Planning only. Nothing below is implemented yet.**
> Follow CLAUDE.md: write code plus pure-JVM tests, bump the version, mark the item in
> MMbank-IMPROVEMENT-PLAN.md, add an entry to MMbank-FEATURE-LOG.md (with "Not covered by CI"),
> push, then wait for CI to go green.

## Context: what the user reported
The user sent a screenshot of Plan → Rewards and asked to "make it better or usable". The Cashback screen
(`ui/screens/cashback/CashbackScreen.kt`) doesn't work in practice:
- **The headline is 0.00 AED.** The planner only runs on 12 spend boxes the user must type by hand, and all 12 are empty.
- **It plans with cards the user doesn't have.** It picks the best of 9 hard-coded UAE cards whether the user holds them or not.
- **The estimates are too high.** A card below its minimum spend (ADCB 365 needs 5,000/mo, ADCB LuLu 3,500/mo) still counts as earning. Once real spend is filled in, ADCB 365's 6% would show up almost everywhere.
- **Payouts take over the screen.** 10 separate Tabby payout rows push the planner off-screen.
- **The screen has no header or back button**, and "AED" is typed into the code (Improvement Plan §4.5).
- **It misses hand-marked cashback.** "Real cashback" filters on `category == CASHBACK_EARNED`, so a row the user sets to kind Cashback in the editor never shows here.

What the user chose:
1. **Cards:** plan only with the cards they tick.
2. **Spend:** fill it automatically from the last 3 months, with an override per category.
3. **Earned cashback:** a summary plus a 6-month chart, with individual payouts behind "See all".

## Domain (pure Kotlin, `domain/cashback/`, each piece with unit tests)

### `CashbackCatalog`
- Add `const val CURRENCY = "AED"`. Every card's cap and fee is in AED.
- The UI uses this constant wherever it now has the literal `"AED"`.

### `CashbackSpendClassifier.classify(tx, categoryName: String?): String?` (new)
- Returns a catalog category key, or null when the spend never earns cashback: ATM, Fees, transfers.
- Match on merchant, then raw message, with the same whole-word approach as `MerchantCategorizer.matches`.
  Copy that helper or make it `internal`.
- AED rules, checked in this order:
  1. LuLu → `GROCERIES_LULU`.
  2. Carrefour, Spinneys, Union Coop, Waitrose, Choithram, West Zone, Grandiose, Aswaaq, Nesto,
     supermarket/hypermarket → `GROCERIES_CARREFOUR`.
  3. ADNOC, ENOC, Eppco, Emarat, Salik, petrol/fuel → `FUEL_SALIK`.
  4. `BillDetector.isBiller(merchant)`, plus SEWA/ADDC/FEWA → `UTILITIES_TELECOM`.
  5. "noon food" → `DINING_DELIVERY`. This has to come before noon.
  6. Talabat, Deliveroo, Zomato, Careem Food, restaurant/cafe/coffee and so on → `DINING_DELIVERY`.
  7. VOX, Reel, Novo, cinema, Platinumlist, Ticketmaster → `CINEMA_ENTERTAINMENT`.
  8. Shein, Namshi, 6thStreet, Ounass → `ONLINE_FASHION`.
  9. Temu, AliExpress → `FOREIGN_ONLINE`.
  10. Amazon, noon → `AMAZON_NOON`.
- Non-AED rules:
  - Netflix, Spotify, OpenAI/ChatGPT, Apple, Google, YouTube, Adobe, Microsoft, iCloud, Anthropic/Claude,
    Canva → `USD_SUBSCRIPTIONS`.
  - Temu, AliExpress → `FOREIGN_ONLINE`.
  - Anything else → `TRAVEL_FX`.
- Fallback when no keyword matches:
  1. The transaction's top-level category name: Groceries, Dining, Fuel or Utilities map to the matching
     bucket; Fees and Transfers return null.
  2. Otherwise `tx.spendingCategory`.
  3. Otherwise `GENERAL_RETAIL`.

### `CashbackSpendEstimator.estimate(txs, categoryNameOf, today, zone, convert): SpendEstimate` (new)
- Uses the same window as `BudgetListBuilder`: the 3 full months before the current one.
- Trims the window to start no earlier than the month of the first transaction. If no full month is
  left, uses the current month so far and sets `basis.partial = true`.
- Counts only rows where `LedgerMath.affectsSpend` is true, using `spendDelta`, so refunds subtract.
  Non-AED amounts go through `CategoryBudgetCalculator.converter(CURRENCY, rates)`. Rows that can't be
  converted go into `uncountedByCurrency`.
- Returns `byCategory` (monthly average, 2 decimal places), `basis` (first month, last month, month
  count, partial flag) and `uncountedByCurrency`.

### `CashbackEngine.compute(cards, categories, spend, countFees: Boolean = true)` (rework)
- **Search.** Try every subset of `cards` (9 cards means 512 subsets; above 14 cards, fall back to plain
  best-rate routing). Within a subset, each category goes to the highest-rate card, with catalog order
  breaking ties. A subset is invalid if any card it routes spend to is below that card's
  `minMonthlySpend`. Caps apply as they do today.
- **Objective.** Maximise gross minus the fees of the cards that are used. With `countFees = false`,
  fees don't steer the choice. That mode is for "your cards", because fees on cards the user already
  holds are paid either way.
- **Alerts.** These name the min-spend problem clearly, e.g. "ADCB 365 pays most on Dining but needs
  5,000 AED/mo on it; your spending would put 1,800 there, so it's left out." Cap-reached and
  fee-beats-cashback alerts stay as they are.
- **Output.** `CashbackPlan` keeps its current shape.

### `CashbackPlanner.build(estimate, overrides, heldIds: Set<String>?, catalog): CashbackPlanView` (new)
- Effective spend for each category is the override if one exists, otherwise the auto figure.
- When `heldIds` is not null, `yours = compute(held cards, countFees = false)`. Its net subtracts the
  fees of every held card.
- `best = compute(all cards, countFees = true)`.
- Each row carries: category, spend, autoSpend, isOverride, your routing, best routing, and
  `extraPerMonth` (best minus yours for that category).
- Totals: your net, best net, `upgrade = best − yours` (only when positive), and the best-set cards the
  user doesn't hold ("Getting FAB Cashback would add about 45 AED/mo").
- Row order: categories with spend first, largest first, then the rest.

### `CashbackEarned.summarize(txs, today, zone, convert): EarnedSummary` (new)
- Counts as cashback: `kind == CASHBACK`, or `category == CASHBACK_EARNED` on a credit. This fixes the
  rows marked by hand.
- Returns, in AED: this month, last month, all time, and a 6-month series (`BarEntry`-ready labels and
  values).
- Also returns `bySource`, using `BankNames.displayName(sender) ?: merchant ?: "Other"`, with all-time
  total, this month, count and latest date.
- Also returns every payout, newest first.

### Codecs and preferences
- `CashbackSpendCodec.encode` keeps zero values (`>= 0`), so an override of "0" means "I won't spend
  here". Negative values are still dropped.
- `cashbackSpendRaw` now holds overrides only. No migration is needed: values typed in the past were
  already the user's own numbers.
- New `AppPreferences.cashbackCardsRaw: String?`, comma-separated card ids. Null means the user has never
  chosen. A `CashbackCardsCodec` (or two functions on the existing codec) encodes and decodes it,
  dropping unknown ids.
- Backup: add `cashbackCardsRaw: String? = null` to the payload and set `formatVersion = 25`, with a line
  in the version doc comment. A restore applies it only when it is non-null.

## UI: `ui/screens/cashback/`
Split the file into `CashbackViewModel.kt` and `CashbackScreen.kt`.

### ViewModel
- Combines `observeTransactions`, `categoryRepository.observeAll` (for category names),
  `ratesRepository.observeRates`, `hideBalances`, and two local `MutableStateFlow`s backed by
  preferences (overrides, held ids).
- Builds `CashbackUiState(earned, plan, held, loading)` on `Dispatchers.Default`, with
  `.catch { container.recordError }`.
- Actions: `setOverride(key, amount)`, `clearOverride(key)`, `setHeld(ids)`.

### Screen
- `MMScaffold("Cashback", onBack)`, design-system components only. Uses `LoadingState` and
  `moneyLabel(…, hidden)`.
- **Earned** (shown only when there are payouts; otherwise a small `EmptyState` explaining that Tabby+
  "You earned" SMS arrive here, or the user can mark a row as Cashback):
  - 3 `StatTile`s: This month, Last month, All time.
  - An `MMCard` with a `BarChart` of the last 6 months.
  - One `ListRow` per source.
  - A `ListRow` "See all payouts (N)" that opens an `MMBottomSheet` listing every payout. Tapping a
    payout opens the transaction editor.
- **Your card plan**
  - If no cards have been picked, a `Banner` "Tick the cards you hold…" with a [Pick my cards] button.
  - A summary `MMCard` with three lines:
    1. "≈ X AED a month with your cards", plus "gross − fees · Y a year".
    2. "The best cards for your spending: ≈ Z (+D)", plus the card to get.
    3. The basis, e.g. "From your average spend Aug–Oct: 4,230 AED a month", with a note on uncounted
       currencies.
  - Non-OK alerts as compact warning `Banner`s.
  - Category `ListRow`s:
    - **Title:** the category label.
    - **Subtitle:** "FAB Cashback · 5% · 25.00/mo · best: Mashreq +20", or "None of your cards pays
      extra here".
    - **Trailing:** the monthly spend, with an `MMTag("Edited")` when it is overridden.
    - **Tap:** opens an `MMBottomSheet` with the category's examples, a `MoneyField`, Save, and "Use my
      average (X)" to reset.
  - A row "No spend yet (N)" that expands to the zero-spend categories.
- **My cards** (`SectionHeader` with an "Edit" action)
  - One row per held card: earned per month, the spend routed to it, its fee per month, and `MMTag`s for
    "Cap reached" or "Below minimum".
  - "Edit" opens an `MMBottomSheet` with a `CheckboxListRow` per catalog card, using `notes` as the
    subtitle.
- The footer tip stays.

### Navigation and Plan hub
- `MainActivity`: `CashbackScreen(onBack = popBackStack, onOpenTransaction = { navigate(Routes.transactionEditor(it)) })`.
- Plan hub: `PlanStatus.forEntries` gets a `cashbackThisMonth: BigDecimal? = null` parameter, which gives
  Rewards a status line "68.00 AED earned this month". `PlanViewModel` fills it from
  `CashbackEarned.summarize` over the transactions it already loads.

## Bookkeeping
- `app/build.gradle.kts`: versionCode 209, versionName 0.141.0.
- `MMbank-FEATURE-LOG.md`: add a 0.141.0 entry with a "Not covered by CI" list (screen layout, sheets,
  chart, navigation).
- `MMbank-IMPROVEMENT-PLAN.md`: mark §4.5 "Cashback gets a header and back button, and stops
  hard-coding AED" ✅ (0.141.0).
- `CLAUDE.md` working notes: one short line on where cashback logic lives.

## Tests (pure JVM, `app/src/test/.../domain/cashback/`)
- **`CashbackEngineTest`**: rework the existing cases for min-spend rerouting.
  - Dining 1000 + Fuel 1000 + LuLu 1000 should give Mashreq 50.00, FAB, and Binance 20.00, with alerts
    naming ADCB 365 and ADCB LuLu.
  - Cap and fee cases stay.
  - New case: `countFees = false` keeps a fee card. Dining 3000 + Groceries (Carrefour) 2500 goes to
    ADCB 365 with fees ignored, and to Mashreq + FAB with fees counted.
- **`CashbackSpendClassifierTest`**:
  - LuLu vs Carrefour; Salik counts as fuel.
  - "noon food" is dining; noon is `AMAZON_NOON`.
  - USD Netflix is `USD_SUBSCRIPTIONS`; AED Netflix is `GENERAL_RETAIL`.
  - A USD hotel is `TRAVEL_FX`.
  - ATM and Fees return null.
  - Category-name fallback works.
- **`CashbackSpendEstimatorTest`**:
  - The 3-month average.
  - Trimmed history divides by 2.
  - Only current-month data gives `partial`.
  - A refund subtracts.
  - Transfers and excluded rows are ignored.
  - An unconvertible currency lands in `uncountedByCurrency`.
- **`CashbackPlannerTest`**:
  - An override wins over the auto figure; "0" holds.
  - Null `heldIds` gives no "yours" plan.
  - The upgrade figure and the card to get.
  - Row order.
- **`CashbackEarnedTest`**:
  - A row of kind CASHBACK with category NORMAL counts.
  - This month, last month and all time.
  - The 6-month series has zero months.
  - Grouping by source.
- **`CashbackSpendCodecTest`**: zero is kept, negatives dropped, round-trips for both codecs, unknown
  card ids dropped.
- **`PlanStatusTest`**: the Rewards line.
- **`BackupMappersTest`**: a v24 JSON without the field decodes `cashbackCardsRaw` as null.
- Keep `DesignLintTest` green: no `Color(0x`, `fontSize`, or `RoundedCornerShape(n.dp)`, and no
  `asMoney` in screens.

## Verification
- Commit on `claude/mmbank-local-ai-parsing-4cfi14` and push.
- Wait for the GitHub Actions `test-build.yml` run to finish without pushing again. Read the unit-test
  summary and the APK build; fix and re-push if anything is red.
- A green run publishes the dev APK at
  `https://github.com/xemmy/mmbank/releases/download/dev-claude-mmbank-local-ai-parsing-4cfi14/MMbank-dev.apk`.
  The user then checks it on the device:
  1. Plan → Rewards shows the earned tiles, chart and Tabby source row, with the payouts in the sheet.
  2. Ticking cards fills the plan from real spend.
  3. Editing and resetting a category works.
  4. The back arrow works.
