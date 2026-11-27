# Insights screen design pass: truncation, currency, alignment, FAB clearance

## Context
The screenshot of the Insights tab (dark theme, ~360dp wide) shows these problems:

**Truncated summary tiles**
- Income, Spent and Net cut off as `+Đ4,464…`.
- Cause: each tile is ~1/3 of 328dp, `MMCard` adds 16dp padding on each side, and `AmountText` is `maxLines = 1` with ellipsis.

**Spent tile taller than the others**
- Its caption "↓16% vs previous period" wraps onto 3 lines.
- It's grey, so it's unclear whether ↓ is good or bad.
- It repeats Smart insights' "Spending is 16% down…".

**Mixed currency**
- Big figures draw the official Dirham glyph (`AmountText` in `ui/util/AmountText.kt`, shown as "Đ").
- Sentences use `moneyLabel`, which reads "655.05 AED": Smart insights and the net-worth "▼ 8,249.13 AED since Jul".

**Header wastes space and has no title**
- The global row in `ui/MainActivity.kt` (~392) is only right-aligned search/settings icons.
- Insights adds a second right-aligned row with "(i) Raw ledger" (`InsightsScreen.kt` ~133). The (i) button is 32dp, below the 48dp touch minimum.
- Home adds a third row with its own "Home" title (`DashboardScreen.kt` `DashboardHeader` ~385). Other tabs have no title at all.

**Period chips:** "••• More" shows both a dots icon and a "More" label (`ui/designsystem/PeriodBar.kt` ~52).

**FAB overlaps the last card**
- The global + (MainActivity ~303, shown on Home, Insights and Accounts) covers the Net worth card.
- Insights' list bottom padding is 16dp and Home's is a 16dp gutter. Accounts uses 96dp and Activity 88dp: same problem, inconsistent values.

**Long merchant names:** a raw name like "AL FUTTAIM TRADING LLC, ABU DHABI-AE" appears in Smart insights.

**Your decisions:**
- Use the **Dirham symbol everywhere** in-app. Notifications and shared files keep "AED".
- **Move Raw ledger into the ⋯ period sheet**, and show a chip while it's on.
- **Scope:** the Insights screen plus the shared parts (header, amount rendering, + button spacing, period chips), which also reach the other tabs.

## Changes

### Shared design system
1. **`AmountText` auto-fit** (`ui/util/AmountText.kt`).
   - Add an optional `autoSize: TextAutoSize? = null`. When it's set, render through `BasicText` (foundation 1.10 via BOM 2025.12.00 supports `autoSize` + `inlineContent`) with `TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize)`. Pass the same param through `MoneyText`.
   - Tabular figures (`tnum`) are already there.
   - Pull the glyph-building code into `internal fun AnnotatedString.Builder.appendMoney(display, color)` plus a shared `moneyInlineContent(color)`, so sentences can reuse it.
2. **Compact tile numbers.**
   - New pure `compactMoneyNumber(amount)` in `ui/designsystem/MoneyFormat.kt`:
     - whole units below 1,000,000 ("4,464", "999,999")
     - then "1.06M" and "10.6M"
     - then "1.2B"
   - Plus `moneyDisplayStringCompact(...)` using the same sign rules as `moneyDisplayString`. The TalkBack description keeps the full exact amount.
3. **Money inside sentences.**
   - New pure `ui/designsystem/MoneySentence.kt`: `sealed interface Segment { Text(s); Money(amount, currency, kind?) }`, plus `fun money(...)`/`text(...)` builders.
   - New composable `MoneySentenceText(segments, style, color, hidden)`. It builds one `AnnotatedString` and uses `appendMoney` for each money segment: Dirham glyph for AED, small trailing code for others, the mask when hidden. A Money segment takes the surrounding text colour unless a tone is given.
   - `moneyLabel` stays for notifications, shares and HTML reports, which can't draw the glyph.
4. **`MMCard` content padding.** Add `contentPadding: PaddingValues = PaddingValues(MMSpacing.cardPadding)` (`ui/designsystem/MMCard.kt`) so compact tiles can use 12dp. The default stays the same.
5. **FAB clearance token.** Add `MMSpacing.fabClearance = 88.dp` (56 FAB + 16 margin + 16 gap) in `ui/theme/MMShapes.kt`. Use it as the bottom content padding on:
   - Insights (`InsightsScreen.kt:128`)
   - Home (`DashboardScreen.kt:152`)
   - Accounts (`AccountsScreen.kt:186`, replacing 96)
   - Activity (`TransactionsScreen.kt:451`, replacing 88)
   - Subscriptions (`SubscriptionsScreen.kt` 88)
6. **One tab header.** In `MainActivity.kt` (~392), the tab-root row becomes `TabRootHeader`: the tab's title on the left (`titleLarge`, from `AppTab.label` via `TabRouting.tabFor(currentRoute)`) with search and settings on the right, all on one line, 48dp targets, vertically centred, with a 16dp start inset matching the content gutter. Delete `DashboardHeader` from Home so "Home" isn't shown twice.
7. **`PeriodBar`** (`ui/designsystem/PeriodBar.kt`):
   - The More chip becomes **icon-only ⋯** with `contentDescription = "More periods and options"`. It keeps its selected state for custom or last-N ranges.
   - New optional `sheetOptions: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null` slot, rendered after a divider and an "Options" label in the existing sheet.
   - Detail screens (`PeriodDetailCommon.kt`) pass nothing and are unchanged.

### Insights screen (`ui/screens/insights/InsightsScreen.kt`)
8. **Raw ledger:**
   - Delete the `ledger_toggle` item (~133-172).
   - Pass `sheetOptions` to `PeriodBar` with a `ListRow` titled "Raw ledger", the existing explanation as its subtitle, and a trailing `Switch` bound to `toggleShowAllLedgerTransactions`. This replaces the (i) button.
   - While raw ledger is on, show an `MMAssistChip("Raw ledger · on", ✕)` under the period stepper. Tapping it turns raw ledger off.
9. **Period tiles** (`PeriodTiles`/`PeriodStatTile` ~499-572):
   - `Row(Modifier.height(IntrinsicSize.Min))`, with each tile using `fillMaxHeight()` and 12dp padding, so all three tiles are the same height.
   - Values use `moneyDisplayStringCompact` and `autoSize`, with `titleMedium` as the maximum size. No ellipsis.
   - Move the caption out of the Spent tile to **one full-width line under the row**, coloured by meaning. Spending down is good, so green (`moneyIn`); up is `moneyOut`; about the same is `textSecondary`. Examples: "↓ 16% less spending than last week" / "↑ 18% more than this point last month".
   - New pure `spendChangeLine(percent, previousLabel): Pair<String, Tone>?` replaces `spendChangeCaption`.
   - In `DashboardViewModel.kt` (~1260), `previousLabel` becomes period-specific ("last week", "last month", "last year", "the previous period"), from a new pure `previousPeriodLabel(unit, monthToDate)`.
   - `SmartInsight.SpendTrend` is filtered out of the Smart insights card while `PERIOD_TILES` is visible, so the same sentence doesn't appear twice.
10. **Smart insights:**
    - `insightText` becomes `insightSegments(insight, currency): List<Segment>`, rendered with `MoneySentenceText`.
    - Use `bodyMedium` so it matches the other card bodies.
    - Merchant names go through `MerchantNormalizer.displayName` (`domain/parser/MerchantNormalizer.kt` ~97), so the example reads "Al Futtaim Trading LLC".
11. **Net worth card** (~770-830):
    - Title row: "Net worth" on the left, a "12 months" label on the right in `textSecondary`.
    - The change line uses `MoneySentenceText` with Dirham glyphs: "▼ Đ8,249.13 since Jul · ▲ Đ… vs a year ago".
    - Arrows keep the direction colour, and the TalkBack text says "down 8,249 dirhams since July". Below it sits the balance's own sign (−Đ1,064,795.18), so an arrow is never read as the balance sign.
12. **Other money sentences on the screen.** Convert the remaining `moneyLabel` sentences on Insights and its two detail screens (`CashFlowDetailScreen.kt`, `SpendingDetailScreen.kt`) to `MoneySentenceText`/`MoneyText`. Leave chart/TalkBack-only strings (e.g. `Sparkline.valueLabel`) as text.

### Housekeeping
- Version `0.127.0` / versionCode 184 (`app/build.gradle.kts`).
- In `MMbank-SUBSCRIPTIONS-PLAN.md`, change its version table to "next free version", since 0.127.0 is now taken.
- `MMbank-FEATURE-LOG.md` entry with a "Not covered by CI" list.
- Mark the item in `MMbank-IMPROVEMENT-PLAN.md`.
- One commit: push, wait for CI, fix forward if red.

## Tests (pure JVM, same commit)
- **`MoneyFormatTest`:** `compactMoneyNumber` covering 0, 4,464.37→"4,464", 999,999, 1,064,795→"1.06M", 10,640,000→"10.6M", 1.2e9, negatives and signs; the compact display string with hidden → mask.
- **New `MoneySentenceTest`:** segment building for each `SmartInsight` type (amounts become Money segments, merchant cleaned by `displayName`); hidden keeps segments but rendering masks them (test the pure `plainText(segments, hidden)` fallback).
- **`SpendChangeCaptionTest`, rewritten for `spendChangeLine`:** ↓ is good, ↑ is bad, about 0 is "about the same", null cases. Add a `previousPeriodLabel` test.
- **`ColorContrastTest`:** add `textSecondary` against `surface` and `surfaceRaised` at ≥ 4.5 for every palette in `MMColors.kt`. Ink300 on Ink900 should pass; if any palette fails, adjust that palette's token.
- A test that `MMSpacing.fabClearance` is at least 56 + 16 dp, and a test of the title `TabRouting` maps each tab-root route to.

## Verification
- **CI** (`.github/workflows/test-build.yml`): unit tests and `assembleDebug` both green. That also proves the `BasicText` `autoSize`/`inlineContent` API compiles on the pinned BOM.
- **Not covered by CI (device check by you), at 360dp and 412dp, dark and light:**
  - Tiles show full amounts with no "…", including a 7-digit value and a 1M+ value, and all three are the same height.
  - The comparison line sits on one line under the tiles.
  - Smart insights and Net worth show the Dirham glyph aligned on the baseline.
  - The Net worth card scrolls fully clear of the +.
  - The header shows "Insights" on the left with the icons on the right; Home shows its title once.
  - ⋯ opens the sheet with the Raw ledger switch, and the "Raw ledger · on" chip appears and clears.
  - Hide balances masks every amount, including inside sentences.
  - TalkBack reads full amounts.
