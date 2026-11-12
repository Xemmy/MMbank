# MMbank — Improvement Plan (living tracker)

> **Legend:** ⬜ todo · 🟡 in progress · ✅ done · 📱 done in code, device check pending.
> Mark an item ✅ (with version) in the same commit that delivers it.

## Progress

| Phase | Done | Notes |
|---|---|---|
| Phase 0 — Hotfixes | 13/13 coded (11 ✅, 2 📱 pending device check: 0.3, 0.4) | shipped 0.62.16, CI green |
| Phase 1 — Design system 2.0 | 0 | 0.63.0 |
| Phase 2 — Component library | 0 | 0.64.0 |
| Phase 3 — Navigation & IA | 0 | 0.65.0 |
| Phase 4 — Screen redesigns | 0 | 0.66–0.71 |
| Phase 5 — States, onboarding, brand | 0 | 0.72–0.73 |
| Phase 6 — Accessibility & l10n | 0 | 0.74.0 |
| Part B — Functionality | 0 | |
| Part C — Smart assignment & subscriptions | 0 | |

---

# MMbank — Design Review & Improvement Plan (Pro Guide)

**Reviewed:** `Xemmy/MMbank` @ branch `claude/mmbank-local-ai-parsing-4cfi14`, commit `a9f0fbd` (v0.62.15, versionCode 104, Room schema v31, ~436 files, Kotlin + Jetpack Compose + Material 3, Compose BOM 2025.12, targetSdk 35 / minSdk 26).
**Decisions you made:** (1) **dark-first, with a real working light mode** and an in-app Light / Dark / System switch; (2) **one design**: remove the Classic/Premium switch and the two filter-bar layouts.
**How this was reviewed:** I read the theme layer myself. Three parallel audits covered every screen (Home/Dashboard/Accounts, Activity/Review/Merchant, Settings + secondary screens + widget). Seven skeptic agents then tried to refute the 40 highest-impact claims against the code, and a completeness critic looked for anything missed. Contrast ratios below were computed with the WCAG formula. Everything marked **verified** was confirmed in code. Things that depend on device runtime are marked **test on device**. Nothing could be rendered: the sandbox has no Android SDK.

---

## 0. Context — why a design pass, why now

MMbank's engine is impressive: dual-channel capture, a bilingual parser, dedup, SQLCipher, on-device AI, and 60+ feature releases with good unit-test discipline. The UI grew with it, one feature session at a time, and it now has **design-system drift**:

- **Two colour systems.** Material `ColorScheme` (`ui/theme/Color.kt`) and dark-only `ObsidianTokens` (white-alpha surfaces, near-white text) are mixed on the same screens. On top of that, raw hex values appear in rows and charts.
- **Light mode is broken.** The theme follows the phone setting, but six screens and every "glass" dialog draw a fixed near-black background and then put theme-coloured (near-black) text on it.
- **Text is too small.** The type scale bottoms out at **8sp** (`labelSmall`) and **9sp** (`labelMedium`, `MetaLabel`). These are used **87 times**, including the bottom navigation labels.
- **Navigation is hard to follow.** There are 30+ routes, a "Home" tab and a "Dashboard" tab that renders `InsightsScreen`, 9 link rows at the bottom of that tab, five different header patterns, and 13 secondary routes that highlight no tab.
- **Inconsistency everywhere.** There are 3 credit/debit palettes, 7+ chip styles, 4 card styles, 3 transaction-row designs, 5 corner radii, 12/14/16dp gutters, and 2 filter bars.
- **The privacy promise leaks.** "Hide balances" masks Home only. Activity, the hero card, Debt Planner, Merchant, the account dialogs and the widget show amounts anyway.
- **Platform basics are missing.** There is no edge-to-edge handling (Android 15 forces it), no splash screen, no status-bar icon colour, no transitions or predictive back, a launcher icon that doesn't match the brand, and notification small icons that render as smudges.

**Intended outcome:** one coherent, legible, theme-correct and accessible design system ("Obsidian 2.0") with a small component library. Then a simpler navigation structure, then the screens redesigned on top of those, each step shipped in reviewable PRs that keep CI green.

---

## 1. Scorecard (current state)

| Area | Grade | One-line reason |
|---|---|---|
| Visual identity | **B** | The Obsidian idea is strong: deep canvas, Space Grotesk, the Dirham glyph. But it is applied unevenly and the launcher icon doesn't match it. |
| Colour system | **D** | Two parallel systems, 3 money palettes, dark-only tokens on light surfaces, textMeta on cards at 4.05:1. |
| Typography | **D** | An 8–9sp floor, no line heights, `headlineSmall` = `titleLarge` (21sp), monospace amounts, Bold requested but not bundled. |
| Layout & spacing | **C** | No spacing tokens; 12/14/16dp gutters and card paddings mixed; 290–437-line dialogs. |
| Components | **D** | 47 Material `Card`s + 21 `ObsidianCard`s + 4 hand-built copies; 7+ chip styles; everything is an `AlertDialog`. |
| Navigation & structure | **D** | Home vs "Dashboard", a link-dump tab, 5 header patterns, tabs not highlighted, no transitions. |
| States & feedback | **D** | No loading states anywhere (the empty state flashes on every screen); raw `e.message` shown to users; no undo; silent status messages. |
| Accessibility | **D−** | Text under 11sp, targets under 48dp, 0 semantics on charts, 0 toggleable rows, 0 string resources, lock screen readable by TalkBack. |
| Platform polish | **D** | No edge-to-edge, splash, monochrome icon, notification icon, predictive back or adaptive layout. |
| Copy | **C** | Clear in places, but leaks jargon (AGSL, loopback, regex, DEBIT) and names the same thing 2–3 ways. |

---

## 2. The 12 issues that matter most (executive summary)

1. **Light mode is unreadable** on the Dashboard tab, Accounts, Debt Planner, Report, Merchant and in all glass dialogs. Example: `InsightsScreen.kt:410` "Smart insights" is near-black on #0B0E14. *(verified)*
2. **The text-size floor is 8sp.** Bottom-nav labels use `labelSmall` = 8sp (`MainActivity.kt:251`, `Typography.kt:57-68`). *(verified)*
3. **Edge-to-edge is not handled** while targetSdk is 35. On Android 15, nested `Scaffold`/`TopAppBar`s probably double the status-bar and nav-bar padding, and status-bar icons stay white over light UI. *(code verified; test on device)*
4. **Hide-balances leaks** across Activity, the hero card, the detail sheet, Debt Planner, Merchant, the account dialogs and the widget. The Dashboard tab masks numbers but still leaks sign, trends and proportions. *(verified)*
5. **The lock screen doesn't really lock.** TalkBack can read the ledger underneath it, and the Recents thumbnail shows balances unless "Block screenshots" is on. *(verified)*
6. **Three credit/debit palettes:** `LedgerColors`, `ObsidianTokens.credit/debit`, and raw `#E07A5F/#4ECDC4` in `TransactionsScreen.kt:2137`. Only the first adapts to theme. *(verified)*
7. **Wallet cards put white text on yellow/green** at 1.45–1.59:1 contrast (Cash, Savings, Crypto in `GlassWalletCard.kt`). *(computed)*
8. **The Activity row is dense:** ~4 lines, 3 chips, 2 icon buttons, card + glow + divider. Its touch targets are 24–30dp. *(verified)*
9. **Forms fight the user.** Amounts open a QWERTY keyboard, the date is typed as `YYYY-MM-DD`, there are no IME actions, error fields turn red with no message, and input is lost on rotation. *(verified)*
10. **No loading states.** Every screen flashes "No … yet" before its data arrives, and Home also flashes "USD 0.00". *(verified)*
11. **Navigation is unclear:** "Dashboard" ≠ DashboardScreen, 9 link rows, duplicated routes (`categories` and `settings_categories_people`), and 13 routes that highlight no tab. *(verified)*
12. **There are no design guardrails:** 0 `@Preview`s, 0 screenshot tests, and nothing stops a raw hex or 8sp text from coming back. *(verified)*

---

## Phase 0 — Hotfixes (one PR, ~1–2 days, patch bump → 0.62.16)

These are small, safe and visible. Ship them before the redesign.

| # | Fix | Where | How | Status |
|---|---|---|---|---|
| 0.1 | **Pin dark theme for now.** This removes every light-mode breakage at once until Phase 1 makes tokens theme-aware. | `ui/MainActivity.kt:113`, `res/values/themes.xml` | `MMbankTheme(darkTheme = true, …)`. Set `windowBackground` to `#0B0E14` (not pure black). | ✅ 0.62.16 |
| 0.2 | **Raise the type floor.** | `ui/theme/Typography.kt`, `ObsidianTokens.kt` | `labelSmall` 8 → **11sp**, `labelMedium` 9 → **12sp**, `MetaLabel` 9 → **11sp** with tracking 0.14em → 0.06em, currency code 8.5 → **max(11sp, 0.7em)**. Add `lineHeight` to every style. Bottom-nav labels use `labelMedium`. | ✅ 0.62.16 |
| 0.3 | **Handle edge-to-edge.** | `MainActivity.onCreate` + inner Scaffolds | Call `enableEdgeToEdge()` before `setContent`. Root Scaffold: `contentWindowInsets = WindowInsets(0)` for the top, and let each screen's top bar own the status-bar inset; *or* apply `Modifier.consumeWindowInsets(innerPadding)` on the NavHost column. Nested Scaffolds: `contentWindowInsets = WindowInsets(0)`. **Test on an API 35 device** with gesture nav and 3-button nav. | 📱 0.62.16 |
| 0.4 | **Lock screen privacy.** | `MainActivity.kt:122-140`, `auth/LockScreen.kt` | While locked, wrap `MMbankApp` in `Modifier.clearAndSetSemantics {}`. When app lock is on: `setRecentsScreenshotEnabled(false)` on API 33+, and `FLAG_SECURE` below 33. Give the ⌫ key `contentDescription="Delete digit"`, make the wrong-PIN text `liveRegion = Polite`, and add haptics plus a shake on a wrong PIN. | 📱 0.62.16 |
| 0.5 | **Show the Security status messages.** | `screens/settings/SettingsScreen.kt:1418-1432` | Render `statusMessage`, as the other subpages already do (`:1407`). | ✅ 0.62.16 |
| 0.6 | **Notification toggles respect denial.** | `screens/settings/SmartAlertsSection.kt:35,76-77` | Turn the toggle on only in the permission result callback. Show a "Blocked — Open settings" row when `NotificationPermission.granted` is false. | ✅ 0.62.16 |
| 0.7 | **Back exits bulk mode.** | `TransactionsScreen.kt` (bulk state ~495) | `BackHandler(enabled = bulkMode) { exitBulkMode() }`. | ✅ 0.62.16 |
| 0.8 | **Day labels stay correct across midnight.** | `TransactionsScreen.kt:1048` | Replace `remember { LocalDate.now() }` with a `currentDate` StateFlow that ticks at midnight and on resume. | ✅ 0.62.16 |
| 0.9 | **Transfer editor shows only fields that apply.** | `TransactionsScreen.kt:2829-2877` | Hide Category, Exclude and Currency when `transferMode`. Add `KeyboardType.Decimal` to the amount fields (2693, 2761). | ✅ 0.62.16 |
| 0.10 | **Crypto amounts aren't rounded to 0.00.** | `ui/util/Format.kt:12-13` | Take fraction digits from `Currency.getInstance(code).defaultFractionDigits`, with a crypto override (up to 8 significant decimals). Unit-test it in `FormatTest`. | ✅ 0.62.16 |
| 0.11 | **Wallet-card contrast.** | `ui/components/GlassWalletCard.kt:35-42` | Darken the end stops: mixing 45% `#0B0E14` gives white ≥4.5:1 (Cash `#8C7400` 4.55, Savings `#1F8345` 4.78, Crypto `#856F2A` 4.88). Add a contrast assertion to `WalletCardTest`. | ✅ 0.62.16 |
| 0.12 | **The review highlight is readable.** | `screens/review/components/TransactionDetailReviewCard.kt:218` | Give the highlight `SpanStyle` an explicit dark `color`. Today it is #F1F5F9 on #FFF59D, about 1.02:1. | ✅ 0.62.16 |
| 0.13 | **Bold → SemiBold.** | 9 call sites (e.g. `TransactionsScreen.kt:2284`, `GlassWalletCard.kt:115`) | Bold renders as SemiBold today anyway; say so in code. Drop the Inter *Light* request in `AmountText.kt:75`. | ✅ 0.62.16 |

---

## Phase 1 — Design system 2.0 ("Obsidian 2.0") (minor bump → 0.63.0)

### 1.1 Token architecture (the most important change)

Replace the two systems with **one three-layer model**:

```
Primitives (ui/theme/Palette.kt)       raw ramps: ink-950…ink-50, indigo-*, mint-*, copper-*, gold-*, teal-*, red-*
        │
Semantic tokens (ui/theme/MMColors.kt) @Immutable data class MMColors(...)  ← darkMMColors() / lightMMColors()
        │   provided via LocalMMColors; accessed as MMTheme.colors.textPrimary
        ├──> Material ColorScheme built FROM the same semantic tokens (so M3 Buttons/TextFields/Switches match)
        │
Component tokens (inside ui/designsystem/*)   e.g. TransactionRowDefaults.amountColor(kind)
```

- Delete the colour `val`s in `ObsidianTokens` (keep the file as a deprecated alias for one release, then remove). `LedgerColors`, `GlowPalette` and the raw hex in screens all map onto semantic tokens.
- Add `ThemeMode { SYSTEM, DARK, LIGHT }`, **defaulting to DARK** (dark-first). Store it as a reactive `StateFlow` on `AppContainer`; the CLAUDE.md rule applies because every screen reads it. `MMbankTheme(darkTheme = themeMode.resolve(isSystemInDarkTheme()))`. Keep the status-bar and nav-bar icon appearance in sync with it.
- Draw **one canvas** at the root, and make every screen container transparent. Today some screens paint `obsidianCanvas()` themselves and others use opaque Scaffolds, which is also why the background presets barely show.

**Semantic colour tokens.** Ratios were computed; pin them all in `ColorContrastTest`.

| Token | Dark | Light | Used for |
|---|---|---|---|
| `canvas` | `#0B0E14` | `#F5F6F8` | app background |
| `surface` | `#131823` | `#FFFFFF` | cards, rows |
| `surfaceRaised` | `#1B2130` | `#FFFFFF` + border | menus, selected rows, inputs |
| `surfaceOverlay` | `#1E2535` | `#FFFFFF` | dialogs, sheets (solid; blur only on the scrim) |
| `borderSubtle` / `borderStrong` | `#232A3A` / `#333C4F` | `#E4E7EC` / `#CBD2DC` | hairlines, focus |
| `textPrimary` | `#EDF1F6` (15.7:1 on surface) | `#0B0F17` (19:1) | titles, amounts |
| `textSecondary` | `#A7B0BF` (8.1:1) | `#4B5563` (7.6:1) | subtitles, meta |
| `textTertiary` | `#7D8699` (4.9:1) | `#5F6673` (5.3:1) | captions only; never under 12sp |
| `accent` / `onAccent` | `#818CF8` / `#0B0E14` (6.5:1) | `#4F46E5` / `#FFFFFF` (6.3:1) | primary buttons, selection. Today's `#6366F1` with white text is **4.47:1 and fails AA**. |
| `moneyIn` | `#6EE7B7` (11.6:1) | `#047857` (5.5:1) | credits, with "+" |
| `moneyOut` | = `textPrimary` | = `textPrimary` | spending, with "−" (see the principle below) |
| `moneyNeutral` | = `textSecondary` | = `textSecondary` | transfers, adjustments |
| `danger` / `warning` / `info` | `#F87171` / `#FBBF24` / `#60A5FA` | `#B91C1C` / `#B45309` / `#1D4ED8` | overdue, over budget, errors, alerts |
| `brandGold` / `brandTeal` | `#F0BE5A` / `#2DD4BF` | `#8A5A00` / `#0F766E` | Binance / Tabby accents |
| `chart1…chart8`, `chartOther` | validated categorical set | light variants | charts and category swatches (`CategoryPresets` maps onto these) |

**Pro principle: calm money colours.** Spending is the normal state of a ledger. Painting every debit red or copper makes the app feel like an alarm. Show debits in `textPrimary` with a "−" sign, credits in `moneyIn` with "+", and transfers in `moneyNeutral`. Keep `danger` for things that need action (over budget, overdue, failed capture). This is the convention used by top fintech apps. If you prefer coloured debits, use a soft copper, but only through the `moneyOut` token.

**Brand accents per account, not string matching.** `GlowTones.forAccountOrigin` checks for "binance"/"tabby" in names. Instead, add `Account.accentColor: Long?` (schema v32; per CLAUDE.md, mirror it in Entity, Model and both mappers, add a round-trip test and an explicit `MIGRATION_31_32`). Default it from a small bank registry (`domain/model/BankNames.kt`). Render it as a 3dp leading accent bar or avatar tint, and drop the per-row radial blooms.

**Ambient background.** Keep it as the signature, but make it real:
- Remove the 88% scrim (`AmbientBackground.kt:88`).
- Draw the glows at 0.10–0.18 alpha in a top band behind the Home hero and the tab headers only.
- Give every preset both a dark and a light variant. Presets: **Obsidian** (default), **Aurora**, **Warm**, **Off**.
- Under "Off", or when Reduce Motion / Battery Saver is on, draw nothing.

### 1.2 Typography

- **Two families, not three.** Space Grotesk for display and titles; Inter for everything else, *including amounts*. Inter's bundled TTFs contain `tnum` (verified by parsing the GSUB tables). Drop IBM Plex Mono, whose monospace digits read like a terminal next to Inter. Use `fontFeatureSettings = "tnum"` on every amount.
- Define **all 15 M3 styles**. Today `headlineMedium` falls back to the M3 default size, and `headlineSmall` = `titleLarge`. Set line heights everywhere.

| Style | Family / weight | Size / line | Use |
|---|---|---|---|
| displaySmall | Space Grotesk SemiBold, −0.02em, tnum | 36 / 44 | Home net worth, detail-sheet amount |
| headlineLarge | Space Grotesk SemiBold | 28 / 36 | tab-root large title |
| headlineMedium | Space Grotesk SemiBold | 24 / 32 | stat hero values |
| headlineSmall | Space Grotesk SemiBold | 22 / 28 | dialog/sheet titles |
| titleLarge | Space Grotesk SemiBold | 20 / 28 | top-app-bar title |
| titleMedium | Inter SemiBold | 16 / 24 | card titles, row primary text |
| titleSmall | Inter SemiBold | 14 / 20 | section headers |
| bodyLarge / bodyMedium / bodySmall | Inter Regular | 16/24 · 14/20 · 12/16 | inputs · body · row secondary |
| labelLarge / labelMedium / labelSmall | Inter Medium | 14/20 · 12/16 · **11/16 (floor)** | buttons · chips & nav · captions/overline |

- Add an `MMTypography` extension with amount styles: `amountDisplay` 36, `amountTitle` 20, `amountRow` 16, `amountCaption` 13, all Inter or Space Grotesk tnum. The currency code is 0.7em with an 11sp minimum.
- Overline (`MetaLabel`) = `labelSmall`, uppercase, +0.06em tracking, `textSecondary` (not 48% alpha).
- **Large fonts:** amounts never ellipsize their digits (today `AmountText.kt:95-103` turns "Đ46,122" into "Đ46,12…"). Use `BasicText(autoSize = TextAutoSize.StepBased(minFontSize = 14.sp))`, available in the pinned BOM, and fall back to compact "Đ46.1K" with the full value in `contentDescription`. Replace `height(x)` with `heightIn(min = x)` on chips, the search field and the chart rows.

### 1.3 Spacing, shape, elevation

- **`MMSpacing`:** 2 · 4 · 8 · 12 · 16 · 24 · 32. Screen gutter **16** everywhere (today 12 or 16). Card padding **16**. Gap between cards **12**. Gap between sections **24**.
- **`MaterialTheme.shapes`:** extraSmall 8 (tags), small 12 (chips, fields, avatars), medium 16 (cards and rows; replaces 18), large 24 (dialogs), extraLarge 28 (sheets), pills = `CircleShape`. No other literal radii.
- **Elevation:** use tonal steps (`canvas` → `surface` → `surfaceRaised` → `surfaceOverlay`) plus a 1dp `borderSubtle`, and no shadows. Glass becomes a solid `surfaceOverlay`, with blur kept only on the *scrim* behind overlays on API 31+ (`GlassDialogBackdrop`). That fixes the always-dark glass dialogs in light mode.

### 1.4 Iconography & glyphs

- One icon style: Material Symbols **Rounded**, 24dp, outlined when unselected and filled when selected. Replace the ▲▼ ↑↓ ⚠ ⚠️ ✓ ★ text glyphs with icons and proper `contentDescription`s. TalkBack currently reads "black up-pointing triangle".
- Category emoji stay (users like them), but always inside a tinted 40dp `CategoryIcon` tile, with the category name as the semantics label.
- The AED Dirham glyph is used everywhere money is shown, through `MoneyText`. Today only 7 call sites use it and ~125 print "1,240.00 AED". Its accessibility `alternateText` becomes "AED" (today "Đ"), and money nodes get a `contentDescription` like "spent 46 dirhams 12 fils".

### 1.5 Motion & haptics

- **`MMMotion` tokens:** short 150ms, medium 250ms, long 350ms; emphasized easing for enter/exit.
- **NavHost:** tab switches fade through (~200ms); pushes slide on the X axis plus a fade (~300ms), with matching pop transitions. Today everything is the default 700ms crossfade.
- Set `android:enableOnBackInvokedCallback="true"` to get predictive back.
- Honour reduce motion (`ANIMATOR_DURATION_SCALE == 0`): skip springs and the AGSL shader.
- **Haptics:** a confirm tick on save, on reaching the swipe threshold, on entering bulk select and on PIN success; a reject pattern on a wrong PIN.

### 1.6 Merge Classic/Premium (your decision)

- Delete `DesignMode`, `LocalDesignMode`, `DesignSettingsScreen` (`SettingsScreen.kt:1750-1803`) and its route. Keep the stored pref key unread, for backup compatibility.
- The AGSL AI indicator turns on automatically on API 33+ when reduce-motion is off. The detail spring always runs, under the same condition. The Activity hero card is always present and collapsible.
- Delete `FilterBarLayout`, the CLASSIC `ActivityFilterBar` (`TransactionsScreen.kt:1721-2011`, about 290 lines) and its setting. Keep the single-row `TransactionFilterBar`.

---

## Phase 2 — Component library (`ui/designsystem/`) (minor bump)

Build each component once, with a `@Preview` in light, dark, 200% font and RTL. Then migrate the screens to it. Every component reads `MMTheme` tokens only.

| Component | Replaces | Spec highlights |
|---|---|---|
| `MMTopBar` / `MMScaffold` | 5 header patterns (`SettingsSubpage` Row, custom rows, missing headers on Rules, Cashback and RuleEditor) | Tab roots: large title that collapses on scroll, with actions (eye, gear, overflow). Pushed screens: small title, back arrow, actions. Owns the status-bar inset. |
| `MMCard` (default / interactive / status) | 47 `Card` + 21 `ObsidianCard` + 4 hand-built boxes | `surface` fill, 1dp border, 16dp radius, 16dp padding. The interactive variant uses `Card(onClick)` (button role, ripple clipped), with an optional chevron. The status variant adds a leading accent bar for info, warning or danger. |
| `SectionHeader` | ad-hoc titles | `titleSmall` + optional "See all ›" action, 24dp top spacing |
| `ListRow` | dozens of Rows | leading (icon / avatar / category tile), title, subtitle, trailing (value / chevron / switch / checkbox). Heights 56 / 64 / 72. The **whole row is `toggleable`/`selectable`** for switch, checkbox and radio rows (0 today). |
| `MoneyText` | `AmountText` + 125 `asMoney` uses | sign, Dirham glyph, tnum, `moneyIn/Out/Neutral` by `TransactionKind`, autosize, a11y description. **Reads `LocalHideBalances`** and masks itself. |
| `TransactionRow` | 3 row designs (Activity, Uncategorized, Review) | Comfortable and compact densities. Spec in §4.2. |
| Chips (×3) | 7+ chip styles | `MMFilterChip` (selectable, M3 FilterChip, `Role.Checkbox`, ≥48dp via `minimumInteractiveComponentSize`), `MMAssistChip` (action), `MMTag` (non-interactive label). |
| `StatTile`, `ProgressBar`, `BudgetBar`, `Banner` | Home and Insights tiles, the capture-health, uncategorized and review cards | `Banner(tone, title, body, actions)` replaces 4 bespoke alert cards. |
| `EmptyState`, `LoadingState` (skeleton rows/cards), `ErrorState` | flashing "No … yet", raw errors | icon, title, body, primary action. Skeletons are sized like the real content. |
| `MMDialog` (confirm / destructive), `MMBottomSheet` | 31 `AlertDialog`s | Dialogs only for confirmations, with the destructive button in `danger`. **Pickers and filters move to sheets.** The old ModalBottomSheet rendering bug is very likely the inset problem from 0.3. Re-test after 0.3 and keep a dialog fallback behind a constant. |
| `MoneyField`, `DateField`, `CurrencyField`, `SearchField` | 63 text fields with 6 `KeyboardOptions` | Decimal keypad, `ImeAction.Next`/`Done`, `supportingText` errors ("Enter an amount like 125.50"), DatePicker/TimePicker, currency dropdown, `ImeAction.Search`. |
| `charts/` (`BarChart`, `DonutChart`, `Sparkline`, `Heatmap`, `PacingGauge`) | `CanvasCharts.kt`, `DonutChart.kt`, `SpendingHeatmap.kt`, the Box-built cash-flow bars | chart tokens; real slice gaps (stroke in `surface` or angular gaps, not 5.5% white); a centre total; tap a value to see it; a `semantics { contentDescription = "Spend by category: Dining 34%, …" }` summary; hidden-mode rendering (flat placeholder, no proportions). |

**Hide balances, done right:** provide `LocalHideBalances` at the root from `container.hideBalances`.
- `MoneyText`, every chart and the percentages all honour it.
- Add an eye toggle in the Home, Accounts and Activity top bars.
- The widget shows "Hidden" when hide-balances is on and "Unlock MMbank to view" when app lock is on.

---

## Phase 3 — Navigation & information architecture (minor bump)

### 3.1 Tabs

| Today | Proposed |
|---|---|
| Home · Activity · **Dashboard** (renders `InsightsScreen`, ending in 9 link rows) · Accounts · **Settings** | **Home · Activity · Insights · Plan · Accounts**, with Settings behind the ⚙ in the top bar of every tab root |

- **Insights** is all the analysis: period summary, cash flow, categories, people, net-worth trend, plus **AnalyticsScreen merged in** (heatmap, pacing). The Monthly report is a top-bar action, and Copilot is an "Ask about your money" field at the top.
- **Plan** is a new hub: a grid or list of live cards for **Budgets, Bills, Subscriptions, Goals, Debt payoff, Splits, Rewards**. Each card shows status ("2 bills due this week", "Groceries 82% of cap").
- **Inbox:** merge `ReviewTransactionsScreen` and `UncategorizedQueueScreen` into one "To review" screen with two tabs (Needs category / AI suggestions) and a progress line ("3 of 12"). It opens from the Home banner and the Activity banner.
- **Highlighting:** move to **nested navigation graphs** (`navigation(route = "plan_graph") { … }`) and check `destination.hierarchy` instead of `childRoutePrefixes`. Every screen then highlights its tab. Consider type-safe `@Serializable` routes; navigation-compose 2.8 supports them.
- **Duplicates:** remove the `categories` route (keep one Categories screen, reached from Plan → Budgets and Settings → Money). Integrity check lives under Accounts, with a link from Settings → Data.

### 3.2 Settings: 14 flat rows → 8 named groups

| Group | Rows |
|---|---|
| **Capture** | Permissions & health · Import & ignore · Parsing rules · AI parsing |
| **Money** | Currency & exchange rates · Monthly budget · Categories · People |
| **Notifications** | every alert toggle, moved out of Preferences, each with permission state |
| **Appearance** | Theme (System / Dark / Light) · Ambient background · Hide balances by default |
| **Security & privacy** | App lock & PIN · Block screenshots · Hide in Recents |
| **Data** | Backup & restore · History retention · Integrity check |
| **Connections** | Binance |
| **About** | Diagnostics · Demo mode · Version |

- Use one `MMTopBar` for every subpage. Its header does not scroll away.
- Show status inline, e.g. "App PIN set" as a confirming snackbar.
- One Save per form, not three Save buttons with a status line at the bottom.

### 3.3 Adaptive layouts

- Use `NavigationSuiteScaffold` (material3-adaptive): a bottom bar on compact screens, a rail on medium and expanded.
- Constrain content to `widthIn(max = 640.dp)`.
- On expanded width, Activity becomes list-detail.
- Use `rememberSaveable` (or `SavedStateHandle`) for all form fields and dialog flags. Today there is only one `rememberSaveable`, so rotation, fold or a theme change wipes typed input.

---

## Phase 4 — Screen-by-screen redesign (one PR per screen, minor bumps)

Order: Home → Activity (+ detail + editor) → Insights → Accounts (+ new Account detail) → Plan tools → Settings → Lock / Copilot / Inbox / Rules.

### 4.1 Home (`screens/dashboard/DashboardScreen.kt`)
```
┌─────────────────────────────────────┐
│ Wed, 11 Nov                 👁   ⚙  │  MMTopBar (large title collapses)
│ NET WORTH                           │  overline
│ Đ 46,122.24                         │  displaySmall, tnum, autosize
│ ▲ Đ1,240 · 2.8% vs last month  ~~~  │  delta + sparkline · "Updated 5 min ago"
├─────────────────────────────────────┤
│ ⚠ Capture may have stopped    Fix › │  AlertStack: one Banner list,
│ ● 12 transactions to review       › │  priority-sorted, max 2 + "2 more"
├─────────────────────────────────────┤
│ Safe to spend today        Đ 182    │  BudgetBar (pacing)
│ [▢ All]  [▢ ADCB]  [▢ Tabby] →      │  accounts carousel (fixed contrast)
│ Recent                   See all ›  │
│ 🛒 Carrefour  Groceries     −Đ86.40 │  TransactionRow compact ×5
└─────────────────────────────────────┘
```
- Up to 3 banners sit *above* net worth today; move them below the hero into one AlertStack.
- Fold Crypto (Binance) into an account card in the carousel, with its detail on Account detail.
- Recent shows 5 rows, not 30. Fix the "Cards due" 32dp inset.
- Show a skeleton while loading, instead of "USD 0.00" plus the empty text. Add pull-to-refresh, and keep the refresh slot at 48dp so the layout doesn't jump.
- Empty state: a **Setup checklist** card (see 5.3), not a paragraph.

### 4.2 Activity (`screens/transactions/`)

**Row: today ≈ 4 lines + chips + 2 icon buttons, inside a card, plus a glow, plus a divider → 2 lines, 64–72dp:**
```
[🛒]  Carrefour                         −Đ 86.40
      Groceries · ADCB ••1234             14:20
```
- The raw SMS, rule, person and the edit/delete buttons move to the **detail sheet**. Swipe actions stay, but add `semantics { customActions = [Categorize, Exclude] }` and neutral swipe colours instead of the debit/credit ones.
- Drop the per-row divider (card *or* divider, never both) and the per-row radial glow, which is also a performance cost.
- Excluded rows: 60% alpha + an "Excluded" `MMTag`, no strikethrough.
- **Sticky day header with a day total:** `Today · Wed 11 Nov ………… −Đ 230.10`, on an opaque `canvas` background so rows don't show through.
- **Header:** a pill `SearchField` plus one row of `MMFilterChip`s (Time ▾, Type ▾, Account ▾, More). Active filters show as removable chips and a "Clear all".
  - Snap the collapse on fling, and read `collapseFraction` via `derivedStateOf` or `graphicsLayer` so the screen doesn't recompose on every frame.
- **No results:** `EmptyState("No matches", action = "Clear filters")`.
- **Detail** becomes a sheet (half → full), with sections for Amount & kind, Details (merchant, category, account, person, date) as tappable `ListRow`s, Original message (raw text with the parsed amount and merchant highlighted), and Actions (Edit, Exclude, Delete).
- **Editor** becomes a full screen:
```
✕  New transaction                 Save
          − Đ 0.00                       big MoneyField, decimal keypad, autofocus
  [ Spent ][ Received ][ Transfer ]      segmented, 6 kinds behind "More"
  Merchant      ____________
  Category      🛒 Groceries        ›    sheet picker (+ New)
  Account       ADCB ••1234          ›
  Date          Today, 14:20         ›   DatePicker + TimePicker
  Person        Me                   ›
  ▸ More: Exclude from insights · Note · Paste message (AI)
```
- **Delete** happens immediately, with an **Undo snackbar** (5s). Keep the "ignore similar" option as a snackbar action. For bulk delete, keep a confirm dialog, styled destructive.
- **Performance:** map rows to an `@Immutable TransactionRowUi` in the ViewModel (bank name, account and glow precomputed). Add `contentType` to items, use `drawWithCache` instead of per-draw allocations, and `flowOn(Dispatchers.Default)` for filtering (`TransactionsScreen.kt:341-379`).

### 4.3 Insights (`screens/insights/` + `screens/analytics/`)
```
Insights                         📄 ⋯
[ Ask about your money…            ]      Copilot entry
[Month ▾]     ‹  November 2026  ›         segmented period + stepper
┌ In Đ12,400 ┐┌ Out Đ8,210 ┐┌ Net +Đ4,190 ┐
Cash flow · 6 months       (bars, axis, tap value; follows period)
Spending by category       (donut w/ centre total + legend: name · % · amount)
Daily spending             (heatmap from Analytics, 48dp-hit, labelled)
People · Net worth 12 mo · Smart insights
```
- "Raw ledger" chip → overflow menu item, renamed "Include excluded transactions".
- Person bars use the chart palette, not `error` for the 4th person (`InsightsScreen.kt:907-912`).
- Show errors (today `state.message` is never rendered on this tab).

### 4.4 Accounts (`screens/accounts/`)
```
Accounts                        👁  +
Total                     Đ 46,122.24
CASH & BANK               Đ 38,400.00
 ▌ ADCB Current ••1234     Đ 30,100.00
 ▌ Wio Savings              Đ 8,300.00
CARDS                     −Đ 3,210.00
 ▌ Tabby Card · due in 4 days −Đ 1,050
⚠ 3 differences vs your bank       ›
Plan debt payoff                   ›
```
- Group by type with group totals. One masking format ("•• 1234") everywhere. The carousel title uses the account *name*.
- **New Account detail screen:** balance + sparkline, the account's transactions, and actions (Adjust balance, Edit, Archive, Delete). This replaces the 3 icon buttons per card and the hidden tap-to-edit.
- The **account form** (`AccountDialog`, 290 lines, ~20 fields in a 460dp dialog) becomes a full screen with progressive disclosure:
  - Basics: name, type, currency, last 4 digits, accent colour.
  - Card & loan details: limit, statement day, APR, minimum payment.
  - Matching: senders, card endings.
- The Unassigned concept gets one design, shared with Home.

### 4.5 Plan tools (Bills, Subscriptions, Goals, Debt planner, Splits, Budgets, Rewards)
- Each uses `MMScaffold` + `ListRow` + an extended FAB with a label ("Add bill").
- Each gets an `EmptyState` with a CTA, and a `LoadingState` instead of the empty-text flash.
- Confirm the destructive actions that are unprotected today: rule delete, split-expense delete, Binance remove, AI-key clear. Expose split-group archive (`SplitsScreen.kt:111` is never called).
- Show human enum labels, not `DEBIT`, `MONTHLY` or `EQUAL`.
- Debt planner honours hide-balances, and an error no longer shows "nothing owed".
- Cashback gets a header and back button, and stops hard-coding "AED".

### 4.6 Lock screen, Copilot, Rules
- **Lock:** follows the theme. 72dp round keys. Fixed-width dots (no layout shift past 4 digits). A biometric prompt that opens by itself. Scrollable in landscape.
- **Copilot:** asymmetric bubbles; `ImeAction.Send`; `SelectionContainer` for copying figures; `liveRegion` on new answers; friendly errors with **Retry** (no `${e.message}`); a "New chat" action; suggestion chips that are always available; answer cards with mini-charts and "See transactions".
- **Rules:** add a top bar and back arrow. Raw regex goes behind an "Advanced" disclosure. In the live tester, highlight the captured amount and merchant in place. Show plain "Money out / Money in". Don't prefill "ai.pending." into the name field.

---

## Phase 5 — States, feedback, onboarding & trust (minor bump)

### 5.1 State model
- Each ViewModel exposes `UiState<T> = Loading | Empty | Content(T) | Error(message, retry)`. Never initialise to an empty `Content`, which is the cause of every empty-state flash.
- **Errors:** map exceptions to human copy ("Couldn't update exchange rates — Retry") and never show `e.message` (today it appears in SettingsScreen ×7, Copilot and the Dashboard snackbar). Log the raw error to Diagnostics.
- **One feedback channel:** a root `SnackbarHostState` via a CompositionLocal. Today Home uses a snackbar, Accounts uses red tappable text, and Security shows nothing.

### 5.2 Destructive actions

| Action | Pattern |
|---|---|
| Single delete or exclude (transaction, expense, rule) | do it now + **Undo** snackbar |
| Bulk delete, backup import, wipe, remove PIN, remove Binance keys | confirm `MMDialog`, button styled in `danger` |
| Archive (account, goal, group) | Undo snackbar; archived items stay reachable |

### 5.3 Onboarding (backlog #59)
- A first-run flow with **live permission status** per step, re-checked on resume:
  1. Welcome, with the privacy promise: "Everything stays on this phone".
  2. Notification access, with an **illustrated "Allow restricted settings" walkthrough**. This is the step that actually blocks sideloaded installs.
  3. SMS.
  4. Battery exemption.
  5. Currency & monthly budget.
  6. Optional "Import past messages".
- Every step is skippable. Afterwards a Home "Setup checklist" card stays until all steps are complete, and it replaces today's paragraph-style empty state.

### 5.4 Notifications & widget
- **Notifications:**
  - A real 24dp `ic_stat_mmbank` small icon (all 6 helpers use the launcher foreground today), plus `setColor(accent)`.
  - Every notification deep-links to its screen.
  - Actions: "Mark paid", "Review", "Snooze".
  - A `NotificationChannelGroup` for channels, and `setGroup` + a summary for repeated alerts.
- **Widget:**
  - `GlanceTheme` with day and night `ColorProviders`.
  - System corner radius; `SizeMode.Responsive`; a real `previewLayout`.
  - Honour hide-balances and app lock.
  - Correct the empty copy ("Set a monthly budget in Settings → Money").

### 5.5 Brand assets
- **Launcher icon:** redraw the mark centred in the 66dp safe zone (today the second "M" is clipped by circular masks), in brand indigo on `#0B0E14`, or with an indigo→teal glow. Add a `<monochrome>` layer for Android 13 themed icons.
- **Splash:** use `androidx.core:core-splashscreen` with a per-mode background and the monochrome mark.
- **XML theme:** `Theme.Material3.DayNight.NoActionBar`, with `values-night`, so cold start doesn't flash black.

---

## Phase 6 — Accessibility & localisation (minor bump; backlog #76–80)

- **Targets:**
  - ≥48dp everywhere. Fix `FilterBarChip` (28), `SecondaryActionChip` (24), `MetaChip` (~26), the row icon buttons (30), the clear-search icon (16), the Insights info button (32), the category edit/delete buttons (36) and the heatmap tiles (16).
  - Let `minimumInteractiveComponentSize()` do this, not hand-picked sizes.
- **Semantics:**
  - Row-level `mergeDescendants`, with a description ("Carrefour, spent 86 dirhams 40 fils, Groceries, today 2:20 pm") plus `onClickLabel`/`onLongClickLabel`.
  - Charts get summaries. Selection state goes on chips and appearance cards. Permission status gets text, not only colour.
- **Never colour alone:** sign + colour for money, icon + text for status, a pattern or label on bars.
- **Font scale:** test at 100%, 130% and 200%. Nothing truncates money.
- **Strings:**
  - Extract per screen into `strings.xml`, *after* the file splits (strings.xml has 3 entries today and `stringResource` is never used).
  - Turn on `pseudoLocalesEnabled` for debug builds to catch clipping and RTL problems.
- **Arabic and RTL** (the app already parses Arabic):
  - `values-ar`; mirrored layouts (16 files already use AutoMirrored icons).
  - Locale-aware `MoneyFormatter` and `DateFormats`, keeping `Locale.US` *only* for AI prompts and CSV export.
- **One `DateFormats` object:** "11 Nov", "11 Nov 2026", relative "Today / Yesterday". It respects `DateFormat.is24HourFormat`. Today it is "MMM d" on one screen, "d MMM" on another, and uppercase "TODAY · 10 JUL" on a third.

---

## 7. Copy & naming guide

**Glossary.** Use one name per concept, everywhere.

| Use | Instead of |
|---|---|
| **Activity** (tab) / "transactions" (items) | "Review Transactions", "See transactions" as a place |
| **Insights** | "Dashboard" (tab), "Smart insights" as a place, "Spending analytics" |
| **Plan** | (new hub) |
| **Rewards** | "Cashback", "Cashback rewards" |
| **Subscriptions** | "Subscriptions radar" |
| **People** (Splits members are People too) | "Friends", "Members" as a separate list |
| **Monthly budget** · **Safe to spend today** | "Daily budget", "allowance", "Premium hero card" |
| **Capture** | "Gate-2", "signature", "ingest" |
| **AI parsing** (on-device or online) | "Local AI parsing" when online is selected |
| **Money out / Money in / Transfer** | `DEBIT` / `CREDIT` |

**Rules:**
- Sentence case. Verbs on buttons ("Add bill", not "+").
- Relative time, written the same way everywhere ("in 3 days"; today "due in 3d" and "Renews in 3 days" are mixed).
- Pick one spelling: optimise or optimize.
- Technical detail (AES-256, loopback 127.0.0.1, Android Keystore, regex, AGSL) goes behind "Learn more", never in a toggle's subtitle.

| Before | After |
|---|---|
| "Premium · AGSL indicator + springier motion" | *(removed: one design)* |
| "Sorry, I couldn't get an answer: java.net.ConnectException…" | "Couldn't reach your AI server. Check it's running, then retry." [Retry] |
| "Capture log (newest first: saved, duplicate, ignored, unmatched, failed)" | "Recent captures" + a status filter chip row |
| "⚠️ Sideload trap: toggle greyed out?" | "Can't turn it on? Android blocks this for apps installed from a file — here's the 20-second fix." |
| "Rates: timeout\nBinance: 401" | "Couldn't update rates or Binance. Retry" |

---

## 8. Engineering guardrails (do these alongside Phase 1, so the design can't drift again)

1. **Split the giant files.** This is a no-behaviour-change PR first (backlog #56).
   - `TransactionsScreen.kt` (3,096 lines) → `TransactionsViewModel.kt`, `TransactionsScreen.kt`, `list/`, `row/`, `bulk/`, `editor/`, `dialogs/`.
   - `SettingsScreen.kt` (2,524 lines) → one file per subpage + `SettingsViewModel.kt`.
   - `AccountsScreen.kt` (1,329 lines) → move the VM out; `AccountFormScreen.kt`.
2. **`DesignLintTest`.** This is a pure-JVM unit test, so it runs in the existing CI with no new infrastructure. It scans `app/src/main/java/**/ui/**` and fails on:
   - `Color(0x` outside `ui/theme` and `ui/designsystem`
   - `fontSize =` outside theme
   - any `ObsidianTokens.` use after migration
   - `asMoney(` in screens (must use `MoneyText`)
   - `RoundedCornerShape(<n>.dp)` literals
   - `Modifier.size(<48.dp)` on `IconButton`
   - Theme-side assertion: no `TextStyle` under 11sp.
3. **Extend `ColorContrastTest`** to every semantic text/surface pair in both themes (≥4.5 for text, ≥3 for large text, icons and chart marks), and to the wallet-card gradients.
4. **Previews.** Add a `@MMPreviews` multipreview (Dark, Light, fontScale 2.0, RTL `ar`) on every designsystem component and on every screen's stateless content composable. That forces screens into stateful wrapper + stateless content pairs, which is better architecture anyway.
5. **Screenshot tests** (backlog #73). Use **Roborazzi + Robolectric**, which runs on the JVM and fits the CI shape. Golden images for the designsystem components and the 5 tab roots, in both themes and at 200% font. Check AGP/SDK-35 compatibility; if it doesn't work, use Paparazzi.
6. **Performance.** Baseline profile (backlog #74), `@Immutable` UI models, stable lambdas, `contentType`, `derivedStateOf` for scroll-driven values, no allocations inside `drawBehind`.

---

## 9. Delivery plan

| Step | PR | Size | Version (per CLAUDE.md) | Depends on |
|---|---|---|---|---|
| 1 | Phase 0 hotfixes | S | 0.62.16 (patch) | — |
| 2 | File splits (no behaviour change) | M | — (refactor; bump only if APK behaviour changes) | — | ✅ TransactionsScreen split (VM + editor dialogs, run 424); SettingsScreen split (VM + backup/diagnostics) and AccountsScreen split (VM + account form), run 428 |
| 3 | Tokens 2.0 + ThemeMode + merge Classic/Premium + DesignLintTest + contrast tests | L | 0.63.0 | 1 | 🟡 3a tokens + ThemeMode + contrast tests ✅ 0.63.0; 3b merge Classic/Premium ✅ 0.63.1 (run 432); 3c DesignLintTest ratchet (CI pending) |
| 4 | Designsystem components + previews + Roborazzi | L | 0.64.0 | 3 | 🟡 0.64.0: MMCard, SectionHeader, ListRow, chips, StatTile, MMProgressBar, Banner, MoneyText, MoneyField, SearchField, states, MMDialog, MMBottomSheet, MMTopBar/MMScaffold, @MMPreviews (CI pending); charts, TransactionRow, Roborazzi ⬜ |
| 5 | Navigation & structure (tabs, Plan hub, nested graphs, Settings regroup, transitions, predictive back, adaptive) | M | 0.65.0 | 4 |
| 6–11 | Screens: Home → Activity → Insights → Accounts (+ `MIGRATION_31_32` for `accentColor`) → Plan tools → Settings/Lock/Copilot/Inbox | 6 × M | 0.66 → 0.71 | 4, 5 |
| 12 | States, undo, onboarding | M | 0.72.0 | 4 |
| 13 | Notifications, widget, icon, splash | M | 0.73.0 | 3 |
| 14 | Strings + Arabic/RTL + locale formatting | L | 0.74.0 | 2, 6–11 |

- **Run Part B.7 step 1 (data safety) before step 9's `MIGRATION_31_32`**, so the first new migration in the redesign is also the first one that is actually tested.
- Remove the dark pin from 0.1 **in step 3**, once light tokens exist. Ship light mode as "Theme → Light" (opt-in, dark stays the default) until the screenshot tests show every screen is clean.
- Each PR: unit tests in the same commit (CLAUDE.md rule), CI green, and a manual device check (below). Update `MMbank-FEATURE-LOG.md`, listing "Not covered by CI".

---

## 10. Verification

- **CI** (`.github/workflows/test-build.yml`): `:app:testDebugUnitTest` must pass. That includes:
  - `DesignLintTest` and the extended `ColorContrastTest`.
  - `MoneyFormatter` and `DateFormats` tests: fraction digits per currency, crypto, masks, locale.
  - Token-resolution tests (light/dark), the `ThemeMode` pref round-trip, the `Account.accentColor` mapper round-trip, and migration SQL v31→v32 in `MigrationSqlTest`.
  - Roborazzi golden diffs once added.
  - `:app:assembleDebug` succeeds.
- **Device checklist** (per PR, from the CI APK):
  1. An Android 15 phone with gesture nav, *and* one with 3-button nav: no doubled top or bottom gaps; status-bar icons readable in both themes.
  2. An API 26–30 device or emulator: the solid-overlay fallback looks right (no blur).
  3. Theme → Light, Dark and System on every tab and in every dialog.
  4. Font size at maximum plus display size large: no money truncation and no clipped chips.
  5. TalkBack: lock screen (nothing readable behind it), an Activity row (description + custom actions), charts (summaries), and settings switches (one focus stop per row).
  6. Hide balances on, then visit every screen and the widget: no amount, sign, trend or proportion visible.
  7. Kill the app with app lock on and open Recents: no balances in the thumbnail.
  8. Rotate during transaction entry: input kept.
  9. Cold start in light mode: no black flash.
  10. Accessibility Scanner on the 5 tab roots: no touch-target or contrast warnings.

---

## Part B — Functionality review (beyond the UI)

**Verdict:** the engine is the strongest part of MMbank: dual-channel capture with dedup, a bilingual normaliser, a rule engine with a live tester, signature routing, AI as a gated fallback (`AiForwardGate` plausibility floor, `LoopbackGuard`, daily budget, redactor), integrity checks against bank balances, capture health, transfer matching, and **1,413 unit tests in 156 files**. The weak spots are (1) **places where money is quietly misreported**, (2) **data safety across 31 schema versions**, and (3) **features that grew in breadth faster than depth**.

### B.1 Correctness: where numbers go quietly wrong (fix first)

| # | Problem (verified) | Why it matters | Fix |
|---|---|---|---|
| B1 | **Foreign-currency charges hit an account 1:1.** A USD 100 charge reduces an AED account by 100. This is documented in `MMbank-FEATURE-LOG.md` (~line 2873), and you chose to keep it. | Every USD subscription and every trip silently corrupts balances, and then net worth, the integrity check and budgets. | Store **original amount + currency** *and* **billed amount in the account currency**. Parse the "AED X equivalent" phrase when the bank includes it; otherwise convert at that day's rate and mark it "≈". Change only `AccountBalanceCalculator` and the parser output. Pin it with the existing named test. |
| B2 | **Today's FX rate is used for every past month** (net-worth history, monthly report, Insights). The report also shows *today's* balances for past months (feature log, Phase 18.17 "Still open"). | History shifts every time rates move, so last March doesn't stay last March. | A daily `exchange_rates_history` table, filled on each refresh and backfilled lazily. `CurrencyMath.toBase(…, onDate)`. Backlog #8. |
| B3 | **Category caps silently skip other-currency spend.** `CategoryBudgetCalculator` is scoped to one `currencyCode`. | You can be over budget while the cap shows under. | Convert (B2) and count it, or at least show "+ USD 45 not counted". Backlog #9. |
| B4 | **Dedup key is coarse:** amount + currency + direction within ±2 min (`Daos.kt:66-82`). It ignores account, card ending and channel. | Two *real* identical charges get dropped, e.g. two AED 4 Salik gates a minute apart, or two coffees. Meanwhile an SMS and a push for the same FX purchase with different amounts are both kept. | Dedup only **across channels** (SMS ↔ push). Two SMS are two purchases unless the body text is identical. Never dedup when both card endings are known and differ. Show merged rows ("Also arrived by SMS") with **Split back out**. Backlog #25. |
| B5 | **No refund/reversal pairing, pending→posted, or partial refunds.** | Spend is overstated; reversals look like income. | Pair by merchant + amount within N days, net them in analytics, and keep the link visible and breakable. Backlog #22–24. |

### B.2 Data safety (the only area with data-loss consequences)

- **31 schema versions, and no migration is ever executed in CI.** `app/schemas/` is not committed, so `MigrationTestHelper` is impossible. Commit the schema JSONs and add Robolectric migration tests, at minimum a "v1 → v31" smoke test (backlog #1–3).
- **Automatic backups:** today backup is manual-only. Add a scheduled encrypted `.mmb` to a chosen SAF folder, weekly plus before every migration (backlog #67). Subscriptions aren't in the backup (re-detected); say so in the UI.
- **Retention prune deletes with no preview.** Show "This will delete 1,240 transactions from 2023" and require a fresh backup first (backlog #58).

### B.3 Capture loop (the growth loop for accuracy)

- **Unmatched → rule in one tap:**
  - Show *why* a message wasn't recognised (#13).
  - Turn on the existing `AiSelfHealingRuleGenerator` draft-rule path from the capture log (#14).
  - Test rules against a **corpus** of past messages, not one sample (`RuleHistoryTester` exists; surface it).
  - Warn on rule shadowing (#16).
- **SMS inbox import** at onboarding (#11). It's the single biggest day-one win, because a new install starts with months of history instead of an empty ledger.
- **Per-sender stats** ("ADCB: 212 captured, 3 unmatched") make capture quality visible (#17).

### B.4 Consolidate overlapping features

| Overlap today | Merge into |
|---|---|
| Bills · Subscriptions · Card payment reminders (`BillReminders`, `SubscriptionReminders`, `CardPaymentReminders`: three recurring-payment models, three reminder paths) | **One "Recurring" model** with types (bill / subscription / card due / income), one calendar view, one reminder pipeline, and "link to the transaction that paid it" (#48) |
| Review queue · Uncategorized queue | **One "To review" inbox** (see Phase 3.1) |
| Settings "People" · Splits "Friends/Members" | **One People list**, used for both assignment and splits |
| Dashboard "Savings goal" card · Goals screen | Goals only; the Home and Insights card shows the top goal |
| Cashback catalog (hard-coded UAE card reward categories such as "Groceries — LuLu", separate from your own categories) | Map rewards onto **your categories plus accounts**, or freeze the feature if you don't use it weekly |
| Analytics screen · Insights tab | Insights (Phase 3.1) |

**Rule of thumb:** for a single-user ledger, every feature has to earn its place in the weekly loop of *capture → correct → understand → plan*. Freeze Splits, Cashback and the demo-data paths unless you actively use them. Each one costs UI surface, backup coverage and migration risk.

### B.5 High-value additions (after B.1–B.4)

1. **Quick add:** a Quick Settings tile and a notification action for cash or manual entry (#61).
2. **Global search:** one search across transactions, merchants, accounts, rules and settings (#57).
3. **Statement reconcile:** extend the Integrity check to import a bank CSV for a month and diff it line by line against captured rows. Paste-import machinery exists.
4. **Envelope or rollover budgets** (#44), building on category caps.
5. **Year in review** and month-over-month deltas on every headline number (#36, #38).

### B.6 Privacy & honesty

- The README still says AI is loopback-only, but `AiProviderMode.ONLINE` sends message text to third parties. Fix the README.
- Show a persistent "Online AI on" indicator in Capture settings.
- Run the redaction audit (#33) before anyone enables Online.

### B.7 Suggested order (functional track, interleaved with the design track)

1. **Data safety:** schema JSONs, migration tests, auto-backup, prune preview. Do this *before* any new migration, including `accentColor`.
2. **Money correctness:** B1 (billed amount), B4 (dedup), B2 + B3 (historical FX, budgets), B5 (refunds).
3. **Capture loop:** SMS inbox import, why-unmatched, corpus testing, one-tap rule.
4. **Consolidation:** Recurring model, one People list, one inbox. This lines up with design Phase 3.
5. **Additions** from B.5.

---

## Part C — Make it smarter: learn from the past, auto-assign person/category, better subscription detection

### C.1 How it works today (verified in code)

**Category at capture** (`data/repository/TransactionRepositoryImpl.kt:150-166`, `domain/parser/CaptureCategoryResolver.kt`):
1. A **learned merchant → category mapping** (`CategoryMatcher`) is checked first: exact name, then normalised brand key (`MerchantNormalizer.key`), then regex. It is learned whenever you set a category on a single row (Activity, Review, Uncategorized, Merchant screen) and backfills that merchant's still-uncategorized rows (`CategoryRepositoryImpl.learnMapping`). Bulk edits deliberately teach nothing.
2. If an AI suggestion is pending for that merchant, the row stays uncategorized for review.
3. Otherwise a **hard-coded keyword list** (`MerchantCategorizer`: plain substring matches on UAE brands, first bucket wins) is bridged onto a same-named category.

**Person:** **no learning at all.** Every capture gets the single default person (`TransactionRepositoryImpl.kt:199`). Setting a person on a row (`TransactionsScreen.kt` `setPerson`, ~596) teaches nothing.

**Account:** rule pin > card number in the message > learned merchant → account, with backfill. **Kind:** learned merchant → kind.

**Limitations:**
- **One mapping per merchant.** It is overwritten on every upsert: no counts, no confidence, and a single mis-tap rewrites the rule.
- **Merchant is the only signal.** Amount, card, account, time and sender are ignored, so an AED 2,000 TV from Carrefour is "Groceries".
- **Substring keywords misfire.** "ace" (shopping) also matches *Palace*, *Space* and *Marketplace*; "mart" matches *Smart*.
- **The app can't tell your choice from its guess.** `categoryId` doesn't record who set it, so it can't safely re-assign later or explain *why*.

**Subscriptions** (`domain/intelligence/SubscriptionRadarEngine.kt`). Runs automatically per merchant after each captured debit (`AppContainer.refreshSubscriptionForMerchant`, ~789), plus a full **Scan** on the Subscriptions screen.
- **Input:** SPEND rows that aren't excluded, grouped by **raw** `merchant.trim().lowercase()` + currency (not the brand key).
- **Needs ≥3 charges.**
- **Every** gap between charges must be within ±2 days of 7 (weekly) or ±5 days of 30 (monthly).
- **Amounts** must be within 5% of the average, or show exactly one price change (an older steady run, then the newest price).
- **Next due** = last charge + 30 days (or + 7).

**What it misses:**
- One late or missed month, or one extra purchase at the same merchant (Apple, Google Play, Amazon), breaks the "every gap" rule, and the whole merchant disappears.
- No quarterly, semi-annual, yearly or bi-weekly cadences.
- "NETFLIX.COM" and "Netflix.com Amsterdam" are different merchants.
- +30 days drifts against real billing dates (the 15th of each month).
- Subscriptions billed in another currency wobble with FX and can fail the 5% check.
- No use of the message text ("renewal", "subscription", "auto-debit", Arabic "اشتراك"/"تجديد").
- No "cancelled / charge missing" state.
- No "mark this as a subscription".

### C.2 Smart auto-assignment, the design ("learns from your past")

All on-device, pure Kotlin, JVM-testable, explainable. No model file and no network.

**1. Record where each value came from.** Add `categorySource` and `personSource` to `transactions` (`USER | LEARNED | ACCOUNT_OWNER | KEYWORD | AI | DEFAULT`).
- The app may re-assign anything except `USER`.
- The detail sheet can say *why*: "Groceries — you chose this for 12 Carrefour purchases".

**2. Replace last-write-wins with an evidence table.**

```
merchant_learning(merchantKey, field[CATEGORY|PERSON|ACCOUNT|KIND], value,
                  votes, lastUsedAt, accountId?, amountBand?)
```
- Every confirmed assignment adds a vote; a correction adds a vote to the new value; votes decay with age (half-life about 6 months).
- Prediction = the value with the highest weighted share, with **confidence = share × min(1, votes/3)**.
- **Auto-apply** when confidence ≥ 0.8. Between 0.5 and 0.8, assign it but mark it "Check" in the To-review inbox with one-tap chips. Below 0.5, fall through to the next signal.
- Migrate the existing `merchant_category_mappings`, `merchant_account` and `merchant_kind` rows in as votes = 3, so nothing already learned is lost.

**3. Use context, not just the merchant (a small nearest-neighbour vote over your own history).** For a new transaction, prefilter past **user-confirmed** rows by brand key *or* card ending (indexed, capped at 500 rows), then score:

| Signal | Weight | Example it solves |
|---|---|---|
| same brand key (`MerchantNormalizer.key`) | 5 | Carrefour → Groceries |
| same card ending / account | 3 | spouse's supplementary card → spouse |
| amount band (log buckets: <20, 20–100, 100–500, 500–2k, >2k) | 2 | Carrefour > AED 1,000 → Electronics |
| same sender / bank | 1 | |
| weekday vs weekend, hour band | 1 | weekday lunch → you; weekend groceries → family |

- Weighted vote per candidate value gives the prediction and its confidence.
- This handles "same merchant, different category by amount" with no rules UI.

**4. Person: three signals cover most of it.**
- Add **`Account.ownerPerson`** ("Tabby Card → Sara"). Order: account owner > learned (merchant × account) > learned (merchant) > default person.
- Start learning from `setPerson` corrections, which are thrown away today.
- One row added to the account form solves most households.

**5. Learn backwards ("apply to similar past transactions").** After a correction, show a snackbar: **"Apply to 14 similar past transactions?"** with Preview / Apply / Undo.
- It only touches rows whose source isn't `USER`.
- It extends today's uncategorized-only backfill to person, and to rows the app guessed earlier.

**6. Cold start.**
- Move the keyword list to a data file with **word-boundary** matching. Reuse the brand registry in `MerchantNormalizer.collapseToBrand`.
- Add a one-screen **"Teach MMbank"** pass: your top 20 unknown merchants by frequency, one tap each. That is usually around 80% of spend volume.
- AI suggestions for the rest stay *pending* (existing flow).

**7. Measure and self-tune.**
- Track overrides of `LEARNED`/`KEYWORD` values, and show "Auto-assigned 90 this month · you changed 6 (93% right)".
- Raise or lower the auto-apply threshold for each field from that rate.
- On the Merchant screen, list what was learned with vote counts, plus **Forget**.

**8. Bulk edits teach, carefully.** When every selected row shares one brand key, count the bulk edit as 1 vote, not N.

### C.3 Subscription detection v2

1. **Group by brand key** (`MerchantNormalizer.key`) + currency. This needs `MIGRATION_x` to re-key the `subscriptions` unique index, which the feature log deferred (~line 3008). Carry ids and dismissed flags across via `SubscriptionMerge`.
2. **Cluster by amount within a merchant first** (±5%, or ±8% for foreign-currency charges). Then detect cadence **per cluster**. "Apple · 3.99 monthly (iCloud)" and "Apple · 24.99 monthly" are found separately, and one-off Apple purchases no longer break anything.
3. **Find the dominant period** instead of requiring every gap to match:
   - Take the median gap and snap it to weekly 7 / bi-weekly 14 / monthly 28–31 / quarterly ~91 / semi-annual ~182 / yearly ~365, with ±15% tolerance.
   - Require **≥70% of gaps** to fit, so one late or missed month is fine.
   - Minimum count: 3 for weekly and monthly, **2 for quarterly and yearly**, when backed by text or brand evidence.
4. **Calendar-anchored next due date:** monthly means the same day next month, clamped to month end. Reuse the anchor-day approach from the 0.62.13 bill fix (`BillAdvancer`), not "+30 days".
5. **Confidence score** = regularity + amount stability + count + **text evidence** + **known-brand list**.
   - Text: "subscription", "renewal", "recurring", "auto-debit", "standing instruction", "membership", "اشتراك", "تجديد".
   - Brands: Netflix, Spotify, Shahid, OSN+, Anghami, YouTube, iCloud, Google One, ChatGPT/OpenAI, gyms, telecom postpaid.
   - **≥0.8 Active**; **0.5–0.8 "Possible — is this a subscription?"** with Yes/No; anything lower is ignored.
6. **Lifecycle:** Active → Due soon → **Charge missing** (expected charge didn't come within tolerance: "Cancelled? or a failed payment?") → Cancelled (you confirm, or 2 periods missed) → Reactivated. Add a **trial** flag (first charge 0 or small, then full price; or "trial ends" text) and full **price history** (backlog #49).
7. **You can teach it:**
   - "Mark as subscription" from any transaction. It is then tracked even with irregular gaps.
   - "Not a subscription" (today's dismiss) suppresses that merchant + amount cluster permanently and feeds the score.
8. **One recurring engine** (Part B.4): bills are *declared* recurring, subscriptions are *detected* recurring, and salary/income uses the same engine in the credit direction (today `CashFlowForecaster` has its own paycheck logic). One status model, one reminder pipeline, one calendar.
9. **Useful outputs:**
   - "9 subscriptions · AED 2,340/year".
   - Overlap detection ("2 music services").
   - "Price went up 18% since March".
   - Upcoming renewals on Home and in the weekly digest (exists; feed it the new states).

### C.4 Delivery and tests
- **Order:**
  - After Part B.7 step 1 (migration tests). Then: `categorySource`/`personSource` + `Account.ownerPerson` (one migration), then the evidence table plus import of existing mappings, then predictor and backfill UI, then Subscriptions v2 (re-key migration).
  - Bump the backup `formatVersion` (`BackupManager.kt` ~184) for the new tables and columns.
  - Follow the CLAUDE.md entity/model/mapper round-trip rule.
- **Pure-JVM tests (synthetic histories):**
  - Spouse's card → spouse.
  - Carrefour bimodal amounts.
  - A correction outvotes stale votes.
  - Never overwrite `USER`.
  - A missed month still detected.
  - Late renewal by 6 days still detected.
  - 31st → Feb 28 anchoring.
  - Apple with 3 clusters + noise.
  - Two price changes.
  - Yearly with 2 charges + renewal text.
  - FX wobble of 6% on a EUR subscription.
  - A dismissed cluster stays dismissed after rescan.
  - "Charge missing" fires once, not every day.

---

## 11. Critical files

- **Theme:** `ui/theme/{Color,ObsidianTokens,Theme,Typography,Fonts,Glass,Glow,AmbientBackground,RowComponents,CurrencyGlyphs}.kt`; `ui/util/{AmountText,Format}.kt`; `res/values/themes.xml` (+ new `values-night/`); `res/drawable/ic_launcher_foreground.xml`; `res/mipmap-anydpi-v26/ic_launcher.xml`.
- **New:** `ui/designsystem/**`, `ui/theme/{Palette,MMColors,MMTypography,MMSpacing,MMMotion}.kt`, `ui/util/DateFormats.kt`, `domain/model/ThemeMode.kt`, `app/src/test/.../ui/DesignLintTest.kt`.
- **Shell:** `ui/MainActivity.kt` (theme mode, edge-to-edge, nav graphs, transitions, lock semantics), `di/AppContainer.kt` (themeMode and hideBalances flows), `data/prefs/AppPreferences.kt`.
- **Screens:** `ui/screens/transactions/TransactionsScreen.kt` (split), `ui/screens/settings/SettingsScreen.kt` (split), `ui/screens/dashboard/DashboardScreen.kt`, `ui/screens/insights/InsightsScreen.kt`, `ui/screens/accounts/AccountsScreen.kt`, `ui/components/{GlassWalletCard,AccountCarousel,DonutChart,CanvasCharts}.kt`, `ui/auth/{LockScreen,PinPad}.kt`, `ui/widgets/DailyBudgetWidget.kt`, `util/*NotificationHelper.kt`.
- **Smart assignment & subscriptions (Part C):** `domain/parser/{CategoryMatcher,CaptureCategoryResolver,MerchantCategorizer,MerchantNormalizer,CategorySuggestionEngine,MerchantAccountLearner}.kt`, `data/repository/{TransactionRepositoryImpl,CategoryRepositoryImpl,MerchantAccountRepositoryImpl}.kt` (recordParsed ~150-199), `domain/intelligence/{SubscriptionRadarEngine,SubscriptionMerge,CashFlowForecaster,BillAdvancer}.kt`, `di/AppContainer.kt` (~789 `refreshSubscriptionForMerchant`), `ui/screens/transactions/TransactionsScreen.kt` (`setPerson`/`setCategoryId` ~596-662), `data/backup/BackupManager.kt` (formatVersion). New: `domain/learning/{AssignmentPredictor,EvidenceVote}.kt`, `domain/intelligence/RecurringDetector.kt`.
- **Data (for `accentColor`):** `domain/model/Models.kt`, `data/local/entity/Entities.kt`, `data/local/AppDatabase.kt` (v32 + migration), `EntityMappersTest`.
- **Reuse, don't rewrite:**
  - `ColorContrastTest`, `GlassSpec` (API gate for the scrim blur), `FilterBarCollapse` (math), `TransactionFilterEngine`.
  - `CaptureHealthEvaluator` (feeds the Home AlertStack and the onboarding checklist), `NotificationPermission`, `DirhamSymbol`.
  - `dayPillLabel` → generalise into `DateFormats`.
