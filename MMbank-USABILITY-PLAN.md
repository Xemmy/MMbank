# MMbank — Part F: everyday usability, one design, optional colour theme

## Context

The screenshots show another finance app (Spendee) doing the daily loop well: one period
control, charts you can tap into, budgets set in one tap, a full-screen transaction page
with note and labels, tidy category / label / wallet management, and home-screen widgets.

MMbank (0.108.0, versionCode 164, schema v37, backup format v16) already has the harder
parts: automatic capture, review inbox, bank-balance check, account history, budgets with
rollover, bills, subscriptions, goals, search. What it lacks is the ease shown above.

Nothing is implemented by this plan.

## Decision: which design

- The other app's **screens** are better made: one big number, a chart, a list, everything
  tappable, large touch targets.
- MMbank **does** far more. Most of its 40 screens (review inbox, integrity check, account
  history, debt planner, bills, subscriptions, goals, splits, rules, capture settings…)
  have no counterpart in the screenshots.

So: **do not replace the design completely, and do not add a second full design.**
1. Bring the other app's way of working into MMbank's **one** design, for everyone (F1–F10).
2. Offer its neutral black + green look as a **colour theme** in Settings → Appearance (F0).
   Similar feel, not a copy: MMbank keeps its own fonts, icons, Dirham glyph and names.

Why not a complete change:
- All 40 screens would be rebuilt at once. CI cannot see screens, so each one would have
  to be checked by hand on the phone.
- Most screens would still need a new design from nothing.
- It discards the design system built in 0.63–0.75.
- A layout switch (Classic / Premium) existed and was removed in 0.63.1 for this cost.

Colours are cheap to switch because they come from one token object (`ui/theme/MMColors.kt`,
~450 uses); layouts are not. After F0 both looks can be compared on the phone; making
Graphite the default, or removing Obsidian, is a small follow-up.

## Found while checking (both are fixed by this plan)

- **Transaction view and editor are two stacked pop-up dialogs** (`TransactionDetailSheet`
  and `TransactionEditorDialog` are `AlertDialog`s, max 460dp tall). The date is typed as
  `YYYY-MM-DD`, the time cannot be set, typed input is lost on rotation, and the editor is
  reachable only from Activity. This is the biggest gap against the screenshots → F3.
- **Two background colours in dark mode.** The root draws the background preset under an
  88% dark layer. With the default preset (Minimal Slate) that gives grey `#26292F` on Home
  and every `MMScaffold` screen, where cards (`#131823`) are darker than the page and
  caption text is 3.99:1 (below the 4.5 floor). Twelve screens with a plain `Scaffold`
  (Activity, Accounts, Bills, Goals, Settings…) paint near-black `#0B0E14` instead.
  Derived from the code, not seen on a device → F0.

## Slices

One slice = one version, pushed alone; wait for its CI run before the next. Every slice:
pure-JVM tests in the same commit, version bump, a ✅ line in a new **Part F** table in
`MMbank-IMPROVEMENT-PLAN.md`, and a `MMbank-FEATURE-LOG.md` entry with "Not covered by CI".

### F0 — Colour theme and one background (0.109.0, no schema change)
- New `domain/model/ColorPalette.kt`: `OBSIDIAN` (default, today's look) and `GRAPHITE`;
  `fromStored()` falls back to OBSIDIAN, like `ThemeMode.fromStored`.
- `ui/theme/MMColors.kt`: the Material roles that are fixed constants in `ui/theme/Color.kt`
  (primary / secondary / tertiary containers, five surface-container steps, error pairs)
  move into `MMColors`, so `toColorScheme()` reads one object. `mmColors(palette, dark)`
  returns one of four sets. Obsidian's two sets keep today's values exactly.
- Graphite dark, contrast already computed (text ≥ 4.5:1 on canvas, surface and raised;
  charts ≥ 3:1): canvas `#0D0E10`, surface `#1A1B1E`, raised `#232428`, overlay `#2A2B30`,
  borders `#2C2D32` / `#43454C`, text `#F3F4F6` / `#B4B8C0` / `#8B9099`, accent `#34D399`
  with `#06281C` on it, accent container `#0F3D2E` with `#A7F3D0`. Graphite light: canvas
  `#F6F6F7`, white surfaces, text `#0E0F11` / `#4B4F57` / `#5F636B`, accent `#047857`.
  Money, status and chart colours stay as in Obsidian ("calm money colours" rule kept).
- Background: `Modifier.ambientBackground` (`ui/theme/AmbientBackground.kt`) fills with the
  palette's `canvas`; a preset only adds its glows. Home and Plan then match Activity.
- Move the last palette-dependent fixed colours onto tokens (`ui/components/CanvasCharts.kt`,
  `ui/components/DonutChart.kt`) and lower their `DesignLintTest` baselines.
- Wiring: `AppPreferences.colorPalette`; a reactive `StateFlow` on `AppContainer` beside
  `themeMode` (both `MainActivity` and `QuickAddActivity` read it);
  `MMbankTheme(darkTheme, palette)`; a "Colours" group with swatches in
  `AppearanceSettingsScreen`; a search entry in `SearchDestinations`.
- Tests: `ColorContrastTest` over all four sets and the Material role pairs; a test pinning
  Obsidian's `ColorScheme` role by role to today's values; `ColorPalette.fromStored`;
  `AmbientBackgroundTest` updated.
- Not covered by CI: how Graphite looks on each screen in dark and light; Home getting
  darker; the cold-start flash stays blue-black (`res/values/themes.xml`); the widget
  keeps fixed colours until F10.

### F1 — One period control (0.110.0)
- New pure `domain/filter/PeriodWindow.kt`: unit `WEEK | MONTH | YEAR | ALL | CUSTOM`, an
  anchor date, `previous()` / `next()`, `label()` ("This week", "Last month", "Mar 2026"),
  `range()`, `buckets()` (days for week and month, months for year and all). Week start is
  a parameter (device locale by default).
- New `ui/designsystem/PeriodBar.kt`: W · M · Y · Max, a ‹ label › stepper, and "…" opening
  a sheet with Last 3 / 6 / 12 months and Custom range (reuse
  `DashboardCustomDateRangeDialog`), so no range available today is lost.
- Replaces `DateRangeFilterRow` in `InsightsScreen.kt`. `DashboardViewModel` maps a window
  onto the existing `DashboardDateRange` (`ThisMonth`, `AllTime`, else `Custom`), so the
  stats code is untouched. Last unit remembered in `AppPreferences` (plain pref).
- Tests: week boundaries, month-end clamping, stepping across a year, labels, bucket
  counts, the mapping onto `DashboardDateRange`.

### F2 — Tap a chart, get a page (0.111.0)
- New pure `domain/report/PeriodBreakdown.kt`: for a window, in/out per bucket, spending
  and income per top-level category (amount, count, share; sub-categories rolled up), and
  transfers / card payments / adjustments as a separate "not counted" group. Spent and
  Received follow `domain/ledger/LedgerMath`; other currencies use the same converter as
  `CategoryBudgetCalculator`, so totals equal Insights.
- New `ui/screens/insights/SpendingDetailScreen.kt` and `CashFlowDetailScreen.kt` (routes in
  `ui/navigation/AppNavigation.kt`, owned by the Insights tab): total, `PeriodBar`, a
  donut / bars / line toggle built on the existing `DonutChart`, `BarChart`, `Sparkline`;
  category rows from `ListRow`. A row opens Activity through `ActivityDrillDown`
  (`categoryIds` + `from` / `to`) and `Routes.activityDrill`.
- `CategorySpendCard` and `CashFlowCard` on Insights get "See all ›".
- Tests: totals equal `LedgerMath`, shares sum to 100, excluded kinds stay out, drill
  string round-trip, `TabRouting` for the new routes.

### F3 — One transaction screen (0.112.0, two commits)
- Commit 1, no visible change: move the editor's fields and rules out of the composable
  into a plain `TransactionEditorState` (amount, currency, kind → direction, date, time,
  account, person, category override, exclude, transfer legs, `valid`, `transferError`)
  with a `Saver`, so input survives rotation. Move the save paths now in
  `TransactionsViewModel` (`addManual`, `updateTransaction`, `addTransfer`, `unlinkRefund`,
  `createCategory`, `parseWithAi`) behind one shared class used by both view models.
- Commit 2: a full-screen route `transaction_editor?id=` with its own
  `TransactionEditorViewModel`, replacing both dialogs:
  ✕ · title · Save; large amount (`MoneyField`, decimal keypad) with currency; kind chips
  (`editorKindsFor`); then `ListRow`s for Merchant, Category (`CategoryPickerBottomSheet`),
  Account, Date and time (Material date and time pickers), Person; an Exclude switch row;
  the "why this category" and refund lines; for a saved row the original message, source,
  History only and Delete; in add mode a folded "Paste a bank message" section.
  Leaving with unsaved changes asks first.
- A + button on Home, Insights and Accounts opens the same screen.
- Tests: validation matrix, kind → direction, cross-currency transfer needs the second
  amount, `Saver` round-trip, route builder and tab ownership.
- Not covered by CI: the whole screen, keyboard, pickers, discard prompt, each entry point.

### F4 — Home at a glance, day totals (0.113.0)
- Home (`DashboardScreen.kt`): below the net-worth hero and alerts, a period card with net,
  money in, money out and mini bars from `PeriodBreakdown`; tap opens Cash flow.
- Recent list grouped by day with a day total. Activity's `DayPill` gets the same total
  (it has none today). Totals come from one pure helper using `LedgerMath`; masked while
  balances are hidden.
- Tests: grouping, totals with refunds, transfers and other currencies.

### F5 — Set a budget in one tap (0.114.0)
- Budgets (`ui/screens/budgets/CategoriesScreen.kt`): a list with **All** (the monthly
  budget preference) and every spending category: this month's spend plus a **Set** pill,
  or the cap and a bar once set. Top 8 by spend, then "All categories (N)".
- Set opens a sheet: amount, a hint ("you spend about X a month", 3-month average), Save,
  Remove. Storage unchanged (`Category.monthlyCap`); status from `BudgetStatusBuilder`,
  `CategoryBudgetCalculator`, `BudgetRollover`.
- Tests: the average, ordering, set and remove through the view model.

### F6 — Notes and labels (0.115.0, schema v38, backup v17)
- `transactions.note TEXT NULL`; tables `labels(id, name, colorHex)` (unique name, NOCASE)
  and `transaction_labels(transactionId, labelId)` with cascade deletes, following the
  `split_group_members` junction pattern. Explicit additive `MIGRATION_37_38`, checked in
  `MigrationSqlTest`; entity / model / mapper round-trips; `BackupManager` carries both.
- Transaction screen (F3): Note field and label chips with "+ New label". Row shows up to
  two labels.
- Activity: label filter in More filters (`ActivityFilters.labelIds`); search and
  `GlobalSearch` also match the note.
- Settings → Money → **Labels**: use counts, rename, delete, merge.
- Spending page (F2): a Labels section with the amount per label.
- Tests: filter engine, merge leaves no duplicate links, note search, backup round-trip.

### F7 — Category housekeeping (0.116.0, schema v39, backup v18)
- `categories.sortOrder INTEGER`, `categories.flow TEXT` (`EXPENSE` default, `INCOME`).
  The migration marks "Income" and any category used only by Received rows as INCOME, and
  seeds Salary, Business, Gifts received and Other income when none exist.
- `flow` only sorts and groups; it never hides a category. Uses: Expenses / Income tabs on
  the Categories screen, income categories listed first when picking for a credit, the
  Budgets list shows expense categories, Insights shows income by source.
- Reorder with move up / down handles; pickers and reports follow `sortOrder`.
- **Merge categories**: a pure `CategoryMerge` plans the moves (transactions, merchant
  mappings, pending suggestions, sub-categories, the kept category's cap or else the
  other's), run in one Room transaction through `CategoryRepositoryImpl`.
- Editor: larger icon and colour grids (`PRESET_ICONS`, `PRESET_COLORS` in
  `ui/components/CategoryEditorFields.kt`), 48dp targets.
- Tests: `CategoryMerge`, ordering, flow grouping, migration SQL, mapper round-trip.

### F8 — Accounts: order, net worth, wealth chart (0.117.0, schema v40, backup v19)
- `accounts.sortOrder INTEGER`, `accounts.excludeFromTotals INTEGER DEFAULT 0`, shown as
  "Count in net worth". An excluded account keeps its balance, history, debts and
  reminders; it only leaves net-worth sums. One pure rule (`NetWorthScope`) used by Home,
  Accounts, the Insights trend, Monthly report, Year in review, financial health and Copilot.
- Accounts screen: a wealth line on top with 7D · 1M · 1Y · Max, from a new daily series in
  `domain/account/BalanceHistory` (ledger replayed per day, past days at recorded rates).
- `ManageAccountsBottomSheet`: the net-worth switch and move up / down per account.
- Account form: a colour row for the existing `accentColor` (it has no picker yet).
- Tests: `NetWorthScope`, daily series against month-end values, mappers, migration SQL.

### F9 — Repeat a manual transaction (0.118.0)
- Transaction screen row **Repeat**: Never / Weekly / Monthly / Yearly. It creates a `Bill`
  (`BillRecurrence`, `BillAdvancer`, bill reminders), so it appears in Upcoming payments.
- Repeats remind; they do not create ledger rows by themselves, because captured payments
  would then be counted twice. "Mark paid" on a bill opens the transaction screen
  prefilled, so a cash payment is one tap.
- Tests: transaction → bill mapping, first due date, prefill arguments.

### F10 — Widgets (0.119.0)
- Three more Glance widgets beside `DailyBudgetWidget`: **Balance** (net worth and the
  30-day change), **Spending** (this month and top categories), **Budgets** (up to four
  categories with bars). One pure snapshot builder decides hidden / locked / empty /
  normal; colours come from the palette tokens, day and night.
- Refresh after each capture and save (today only every 30 minutes); real preview images.
- Settings → Appearance → "Add a widget" (`AppWidgetManager.requestPinAppWidget`).
- Tests: the snapshot builder in all four states.

## Left out on purpose
- A second layout, new tab names: MMbank's five tabs stay.
- Receipt photos: encrypted storage and backup size need a decision first.
- Shared wallets, invites, bank connections: MMbank is on-device and single user.
- "Log Google Wallet payments": capture from notifications already does it.
- The label bubble chart: a list reads better and works with TalkBack.

## Files touched most
`ui/theme/{MMColors,Theme,AmbientBackground,Color}.kt`, `domain/model/ColorPalette.kt`,
`domain/filter/{PeriodWindow,TransactionFilterEngine}.kt`, `domain/report/PeriodBreakdown.kt`,
`ui/designsystem/PeriodBar.kt`, `ui/screens/insights/`, `ui/screens/dashboard/`,
`ui/screens/transactions/` (editor state, new screen, the two dialogs removed),
`ui/screens/budgets/CategoriesScreen.kt`, `ui/screens/accounts/`, `ui/widgets/`,
`ui/navigation/AppNavigation.kt`, `ui/MainActivity.kt`, `di/AppContainer.kt`,
`data/prefs/AppPreferences.kt`, and for F6–F8 `data/local/{AppDatabase,MigrationSql}.kt`,
`data/local/entity/Entities.kt`, `domain/model/Models.kt`, `data/backup/BackupManager.kt`,
`app/build.gradle.kts`.

## Verification
- Per slice: push to `claude/mmbank-local-ai-parsing-4cfi14`, then read the
  `test-build.yml` run: unit tests green (including `ColorContrastTest`, `DesignLintTest`,
  `MigrationSqlTest`, mapper round-trips) and the APK built.
- On a phone, from the CI APK, because CI cannot see screens:
  - F0: both colour themes in dark and light on every tab; Home and Activity now share one
    background.
  - F1–F2: period bar stepping and labels; chart toggle; a category row opens Activity
    with the right dates.
  - F3: add and edit from every entry point; rotate while typing; date and time pickers;
    a transfer between two currencies.
  - F4–F5: day totals match the rows; the Set-budget sheet with the keyboard open.
  - F6–F8: export a backup first, then upgrade v37 → v40 and confirm nothing is lost;
    label filter; category merge and order; excluding an account changes net worth on
    every screen.
  - F9–F10: a repeat appears in Upcoming payments; each widget normal, hidden and locked.
