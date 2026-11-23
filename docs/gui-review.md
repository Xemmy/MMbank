# MMbank GUI Review — `claude/mmbank-local-ai-parsing-4cfi14`

> Produced by a 16-agent adversarial review tournament (spawn → attack → defend →
> judge, 4 rounds) evaluating this branch's Jetpack Compose UI against the actual
> source only (no build/emulator available). This is the surviving, most-attacked
> and best-defended review out of 16 independent passes.

## Verdict: Good, but flawed — not yet great

The design system underneath MMbank is genuinely strong. The screens that *use* it well are close to great. But across ~45 screens a real gap opens up between the token system's ambition and what individual screens actually do with it: touch targets as small as 30dp on the highest-traffic row, 11sp text on every displayed currency amount in the app, a tab bar that silently jumps to the wrong highlighted tab from Insights' own navigation cards, a 1614-line settings router where most of the bulk is the very sub-screens it's accused of hiding, and two screens (Splits, Copilot) that bypass the shared `designsystem/` components entirely in favor of raw Material 3 widgets. None of this is a design-system failure: the foundation is solid, and every problem below is fixable on top of it without touching the tokens or rebuilding navigation.

## What's genuinely excellent (said plainly, not padded)

1. **A real semantic token system, not just a palette.** `MMColors` (`ui/theme/MMColors.kt`) isn't a flat set of hex constants — it's a data class of *roles* (`textPrimary`, `textSecondary`, `moneyIn`, `moneyOut`, `danger`, `chart1..chart8`, `surfaceContainer...Highest`) that both the Obsidian and Graphite palettes implement, in light and dark. `toColorScheme()` derives Material 3's `ColorScheme` from the *same* tokens, so M3's own buttons/switches/fields can never drift from the custom components. That's the right way to build a design system, not the common shortcut of hardcoding colors screen-by-screen.

2. **Contrast is tested, not assumed.** `ColorContrastTestKt` computes WCAG relative-luminance ratios and asserts AA (4.5:1) or AAA (7:1) for every text/surface pair, in both themes, including the chart palette (comment in `MMColors.kt`: "Okabe-Ito-derived... each clears 3:1... Charts still never rely on colour alone"). Very few hobby-scale Compose apps verify contrast with a unit test instead of eyeballing it — and for a bespoke dark "Obsidian" palette, that's exactly where contrast quietly rots without a pinned check.

3. **A disciplined spacing scale that's actually followed.** `MMSpacing` (`ui/theme/MMShapes.kt`) defines one scale (`xxs=2 … xxl=32`, gutter=16, cardPadding=16, cardGap=12, sectionGap=24) and it's the thing screens reach for — `MMSpacing.gutter`, `.cardGap`, `.sectionGap` appear throughout `DashboardScreen.kt`, `ListRow.kt`, `SectionHeader.kt`, `States.kt`. `MMTheme.colors` is referenced extensively across `app/src/main/java`, concentrated in the actual screens — dozens of references each in `InsightsScreen.kt`, `AccountsScreen.kt`, `DashboardScreen.kt`. That is real component reuse, not a design system that exists only in isolation as an unused `designsystem/` folder.

4. **Deliberate, well-reasoned money-color semantics.** `MMColors.kt` explains *why* debits use the primary text color instead of red: "Spending is the normal state of a ledger, so debits use the primary text colour." That's a considered UX decision — treating every purchase as alarming red is both wrong and fatiguing — applied consistently (`darkMMColors().moneyOut = Ink50`, i.e. same as `textPrimary`).

5. **Deliberate states, not placeholders.** `States.kt`'s `EmptyState`, `ErrorState`, and `LoadingState` are not stubs — `LoadingState` draws skeleton rows shaped like a real list row (avatar circle + two text bars) and pulses with `rememberInfiniteTransition`, but respects `rememberReduceMotion()` so motion-sensitive users get a static skeleton instead.

6. **Adaptive layout already exists.** `AppNavigation.kt`'s `AdaptiveLayout` switches `NavigationBar` → `NavigationRail` at 600dp width and caps content at 640dp so a tablet/foldable doesn't stretch cards edge-to-edge.

7. **Global search already covers app features, not just transactions.** `SearchDestinations.kt:35-55` builds a flat `all` list combining the five tabs, `PlanEntry.entries` (Upcoming, Budgets, Bills, Subscriptions, Pay Later, Goals, Debt payoff, Splits, Rewards), standalone tools (Monthly report, Year in review, Copilot, To review, Statement reconcile), and Settings rows. `GlobalSearch.search()` folds these into `SearchKind.DESTINATION` results labeled "Go to" in `SearchScreen.kt`, tappable straight to `onOpen(result.target)` → `navController.navigate(...)` in `MainActivity.kt`. Typing "debt" in the top bar's search already surfaces "Debt payoff" and navigates there in one tap — a meaningfully more complete answer to "can a user find a feature by name" than a 5-item bottom nav alone would suggest.

8. **The lock screen and PIN pad are genuinely well-built security UI, not an afterthought.** `LockScreen.kt` draws a full-screen opaque gate that swallows every pointer event via `pointerInput { awaitPointerEventScope { ... it.consume() } }` (lines 101-106) so nothing underneath is reachable while locked, auto-triggers the biometric prompt on appearance (`LaunchedEffect(Unit) { if (!usePin && biometricAvailable) tryUnlock() }`), and falls back cleanly to a PIN pad when biometrics aren't available or fail. `PinPad.kt` gives real accessibility treatment most hand-rolled keypads skip: a haptic buzz on wrong-PIN (`HapticFeedbackType.LongPress`, line 52), a live region for the error message (`Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, line 75) so screen readers announce "Wrong PIN" without the user having to find it, and explicit `contentDescription = "Delete digit"` on the backspace glyph key — three accessibility behaviors that are easy to omit entirely and weren't.

So: the foundation (`theme/` + `designsystem/`) is not just competent, it is above the bar for a solo/small-team Android app, and the security-gate UI is a genuine, under-advertised strength. The problems below are about where individual screens and the navigation wiring deviate from the foundation's own standards, not about the foundation itself.

## Concrete problems, with evidence and fixes

### 1. Several icon buttons are smaller than Android's and the app's own minimum touch target (Accessibility — Highest priority)

Android's accessibility guidance (and WCAG 2.5.5) calls for a minimum **48×48dp** touch target. Material 3's default `IconButton` already reserves 48dp without any extra work. But several hand-rolled action buttons explicitly override that down to 36dp and 30dp:

`ui/screens/budgets/CategoriesScreen.kt:662` (the overflow/merge button) and the `RowActions` edit/delete buttons in the same row:
```kotlin
IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
    Icon(Icons.Filled.MoreVert, contentDescription = "More actions", modifier = Modifier.size(18.dp))
}
```
This sits in a row that already has a weighted category name (`CategoriesScreen.kt:649-657`):
```kotlin
Text(
    category.name,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    color = MMTheme.colors.textPrimary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.weight(1f),
)
RowActions(onEdit = onEdit, onDelete = onDelete)
if (onMerge != null) { /* 36dp overflow button */ }
```
Because the name `Text` already uses `Modifier.weight(1f)` with `maxLines = 1` and `TextOverflow.Ellipsis`, reverting all three icon buttons (edit, delete, overflow) to the default 48dp `IconButton` size cannot overflow or break the row layout — the weighted text simply absorbs the extra width with a longer ellipsis on long category names. No further measurement is needed; this is a direct, safe fix.

`ui/screens/transactions/TransactionsScreen.kt:1456,1464` — smaller still, at 30dp, on the row that lists every transaction in the ledger:
```kotlin
IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
    Icon(Icons.Filled.Edit, contentDescription = "Edit transaction", modifier = Modifier.size(17.dp), ...)
}
IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) { ... }
```
30dp is 37.5% smaller than the 48dp minimum in both dimensions, on the highest-traffic screen in the app. To be precise about the actual risk: the delete button does not delete on tap. `onDelete = { deleteTarget = tx }` only opens `DeleteWithIgnoreDialog` (title "Delete this transaction?"), and the real deletion — `viewModel.delete(tx.id)` — only fires from an explicit confirm inside that dialog. So a mis-tap costs the user an extra dismiss, not their data. The accessibility problem is real (repeated mis-taps on the screen used most), but it's friction, not data loss.

**Fix** — don't shrink the touch target, shrink only the icon glyph and let the ripple/touch area stay full-size (this is exactly what Material's own `IconButton` default already does correctly elsewhere in the app, e.g. `AccountHistoryScreen.kt:142`):
```kotlin
// Before
IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
    Icon(Icons.Filled.Delete, contentDescription = "Delete transaction", modifier = Modifier.size(17.dp), tint = ...)
}

// After — touch target stays at Material's 48dp default, only the glyph shrinks
IconButton(onClick = onDelete) {
    Icon(Icons.Filled.Delete, contentDescription = "Delete transaction", modifier = Modifier.size(17.dp), tint = ...)
}
```
For `TransactionsScreen.kt` this is close to a one-line fix per button. For `CategoriesScreen.kt`, as shown above, the weighted/ellipsized name text means reverting straight to 48dp is also safe — no `minimumInteractiveComponentSize()` workaround or layout audit required.

### 2. 11sp currency and meta text is below the practical legibility floor, on nearly every screen in the app (Accessibility — High priority)

`ObsidianTokens.kt` pins `metaFontSize = 11.sp` and `currencyFontSize = 11.sp`, next to a `bodyFontSize = 13.sp`, with a comment showing this was a deliberate choice ("spec 5.1"). This is not a minor cosmetic nit: currency text is the single most load-bearing text in a banking app, and it renders at 11sp — below Material/WCAG's practical 12sp legibility floor — on effectively every screen that shows an amount (Dashboard, Transactions, Accounts, Insights, Budgets, and more). The same accessibility standard that makes the 30dp/36dp touch targets in Problem 1 a real defect applies here: small text and small touch targets are both WCAG/Android accessibility failures, and reviewing one as a top-priority fix while waving the other off as "not a problem" would be inconsistent. This is not backed by an automated check the way contrast is (`ColorContrastTestKt` tests color pairs, not font size), so unlike contrast, it is currently unverified at build time.

**Fix:** bump `metaFontSize`/`currencyFontSize` from 11sp → 12sp (matching `bodySmall = 12.sp` in `Typography.kt`) in `ObsidianTokens.kt`. This is a one-line change in a single central token, and because every `MetaLabel`/currency-displaying component reads from this token rather than hardcoding a literal, the fix ripples correctly and consistently everywhere currency and meta text appear — no per-screen edits needed.

### 3. Insights' own navigation cards silently highlight the wrong bottom-nav tab (Navigation correctness — High priority)

`InsightsScreen.kt:264-334` renders `NavigationRow` doorway cards inside the Insights screen for Subscriptions, Budgets, Bills, Goals, and Splits (e.g. line 274-278: `NavigationRow(..., title = "Subscriptions radar", ..., onClick = onOpenSubscriptions)`). But `AppNavigation.kt:107-137`'s `ownerByBaseRoute` map assigns every one of those destinations to a *different* tab:
```kotlin
Routes.SUBSCRIPTIONS to AppTab.PLAN,
Routes.GOALS to AppTab.PLAN,
Routes.BILLS to AppTab.PLAN,
Routes.SPLITS to AppTab.PLAN,
Routes.BUDGETS to AppTab.PLAN,
```
So a user who taps "Subscriptions radar" *from the Insights tab* lands on a screen where the bottom bar highlights **Plan**, not Insights — a jarring "I didn't press that tab" moment. This happens on the default configuration for any user, with zero special setup: Insights is reachable from the bottom nav, its own content includes these doorway cards by design, and the tab-ownership map is unconditional. It's a real information-architecture defect, distinct from (and sharper than) the general "45 screens is a lot" observation, because it actively misleads the user about where they are in the app, not just about how to find a feature.

**Fix (two options, cheapest first):**
- Cheapest: change `tabFor()`'s contract so cross-tab doorway navigations (from Insights into a Plan-owned route) don't force a tab highlight at all — pass an explicit `fromCrossTabLink = true` flag from `InsightsScreen`'s `onOpenSubscriptions`/`onOpenBudgets`/etc. callbacks that tells `AppNavigation` to leave the previous tab highlighted (or none) rather than silently flipping to Plan.
- More correct: give Insights' doorway cards their own sub-route namespace (e.g. `insights/subscriptions_summary`) that renders the same content but is explicitly owned by `AppTab.INSIGHTS` in `ownerByBaseRoute`, so the bottom bar stays consistent with wherever the user actually tapped from.

### 4. Settings is a 1614-line file where most of the bulk IS the thirteen sub-screens, not a thin router over them (Maintainability + GUI consistency risk)

`SettingsScreen.kt` is 1614 lines and names thirteen distinct settings areas. Checking each name against the file tree: only `AppearanceSettingsScreen`, `ImportMessagesSettingsScreen`, and `MerchantRenamesSettingsScreen` are in their own files. The other eight — `CategoriesAndPeopleScreen` (line 370), `CaptureSettingsScreen` (430), `NotificationsSettingsScreen` (533), `PreferencesSettingsScreen` (581), `SecuritySettingsScreen` (754), `AiSettingsScreen` (865), `DemoSettingsScreen` (1108), `BinanceSettingsScreen` (1565) — are `@Composable fun` bodies defined directly inside `SettingsScreen.kt` itself. So 8 of 13 named areas are not split out — they're the bulk of the exact 1614-line file. This matters for GUI quality because a file this size, mixing a router with eight full settings screens, is exactly where visual inconsistency creeps in: it's easy to add "just one more row" with slightly different padding instead of reusing `ListRow`/`SwitchListRow` from the design system, and easy for a reviewer (or a future contributor) to lose track of what's already built versus what needs rework.

**Fix:** split each of the eight inline settings screens (`CategoriesAndPeopleScreen`, `CaptureSettingsScreen`, `NotificationsSettingsScreen`, `PreferencesSettingsScreen`, `SecuritySettingsScreen`, `AiSettingsScreen`, `DemoSettingsScreen`, `BinanceSettingsScreen`) into their own files, the same way `AppearanceSettingsScreen` already is. This is a mechanical move, not a rewrite, and it should happen before any "consistency audit," because right now the audit surface is the entire 1614-line file at once rather than one component at a time.

### 5. Two screens bypass the shared `designsystem/` components for raw Material 3 widgets (Consistency)

`SplitsScreen.kt` and `CopilotChatScreen.kt` use plain `Scaffold`/`TopAppBar`/`Card` (`SplitsScreen.kt:133,136,197,213`; `CopilotChatScreen.kt:238,241,253,349`) rather than the app's own `MMScaffold`/`MMCard` used throughout `BudgetsScreen.kt`, `AnalyticsScreen.kt`, and others. This isn't visually broken — Material 3's defaults are themed via `toColorScheme()` so colors still track the token system — but it means these two screens don't get whatever extra conventions `MMCard`/`MMScaffold` encode (consistent elevation/shape/padding behavior, any shared back-button or title treatment), and a reader auditing "is the design system consistently adopted" will find these two as the exception.

**Fix:** migrate `SplitsScreen.kt` and `CopilotChatScreen.kt`'s top-level `Scaffold`/`Card` calls to `MMScaffold`/`MMCard`, matching the pattern already used in `BudgetsScreen.kt`.

### 6. ~45 screens is still a discoverability problem, even though feature search already exists (IA — Medium priority)

The bottom nav itself is fine: 5 items (`AppTab`: Home, Activity, Insights, Plan, Accounts), which is Material 3's documented sweet spot. "Plan" routes to nine further destinations (`PlanEntry` in `ui/screens/plan/PlanHub.kt`: Upcoming, Budgets, Bills, Subscriptions, Pay Later, Goals, Debt payoff, Splits, Rewards), and contextual drill-downs (`MerchantDetailScreen`, `RuleEditorScreen`, `IntegrityScreen`, `YearInReviewScreen`, `StatementReconcileScreen`, `CashFlowDetailScreen`, Settings' sub-screens) are reachable only from specific parent screens, by design. That's a reasonable IA choice for drill-downs that genuinely are contextual — separate from the cross-tab highlighting bug in Problem 3 above.

What actually answers "can a user find a feature by name" is `SearchDestinations.kt` + `GlobalSearch.kt`: every `PlanEntry`, every tab, several standalone tools, and Settings rows are already indexed and searchable from the top-bar search icon (`MainActivity.kt:405-407`, `Routes.SEARCH`), surfaced as "Go to" results in `SearchScreen.kt`. The current search placeholder, `SearchScreen.kt:96`, already reads `"Search merchants, accounts, rules, settings…"` — it already names non-transaction categories ("rules", "settings"), so this is not a search box that visibly implies "transactions only." The remaining gap is narrower than that: nothing tells a user the search also reaches *destinations/features by name* as a category, distinct from searching merchant or account data — a reasonable assumption for most finance apps' search boxes, which search content, not navigation.

**Fix (prioritized, cheapest first):**
- Add one additional word to the existing placeholder that names the destination-search capability explicitly, e.g. `"Search transactions, or jump to a feature…"` — `SearchDestinations.all` already has everything it needs; this is copy only.
- Add a short in-hub hint or a lightweight filter field at the top of `PlanHub` itself for users already inside Plan who don't want to back out to global search — `PlanEntry` already carries `subtitle` text per entry that could back a simple substring filter.
- Promote the 2-3 most used Plan entries (likely Budgets, Upcoming, Bills) to be reachable directly from Home via existing cards (`DashboardScreen.kt`'s `CardsDueCard` already partially does this) so common paths don't require knowing about Plan or search at all.

## Priority order (most to least impact on perceived GUI quality)

1. Fix the 30dp touch targets in `TransactionsScreen.kt` — smallest code change, highest real-world impact (every transaction edit/delete tap on the busiest screen in the app).
2. Fix the 36dp touch targets in `CategoriesScreen.kt` (edit, delete, and overflow) — safe to revert straight to 48dp given the weighted/ellipsized name text, no measurement needed.
3. Bump `metaFontSize`/`currencyFontSize` 11sp → 12sp in `ObsidianTokens.kt` — one central-token change that fixes legibility of every displayed currency amount in the app.
4. Stop Insights' own navigation cards from flipping the bottom-bar highlight to Plan — fix `tabFor()`/the doorway-card routing so tapping a card from Insights doesn't make it look like the user pressed a different tab.
5. Split the eight inline settings screens out of `SettingsScreen.kt` into their own files, mirroring `AppearanceSettingsScreen` — reduces the 1614-line file to an actual thin router and makes a future consistency pass tractable.
6. Make the existing "search covers features too" capability visible via one word of placeholder copy — it already works, it just needs to be named as a category.
7. Migrate `SplitsScreen.kt`/`CopilotChatScreen.kt` off raw `Scaffold`/`Card` onto `MMScaffold`/`MMCard` for full design-system consistency.

## Bottom line

The task asks whether this has "a great GUI, and can it be improved." The honest answer: the *design system* is great — tokenized, tested for contrast, consistently adopted across real screens (not just theme files), and thoughtfully reasoned (the debit-color decision, the lock-screen's pointer-consuming gate, the PIN pad's haptic/live-region accessibility all show real UX thinking, not boilerplate). The navigation model is also more capable than a first glance at the 5-item bottom nav suggests, because feature search already exists and already covers the ~45-screen surface area. What keeps the whole app from being great is narrower than it first appears but touches more than one axis: a handful of screens quietly undercut the system's own 48dp touch-target standard on the highest-traffic list in the app, every displayed currency amount renders below the practical legibility floor, Insights' own in-screen navigation cards silently mislead the user about which tab they're on, the Settings root file conflates a router with eight full sub-screens instead of being a thin router over them, two screens (Splits, Copilot) opt out of the shared component library, and a capability that already solves the "45 screens, can I find one" problem is invisible unless a user already knows to try it. All of these are fixable without touching the token system or rebuilding navigation — which is exactly why they're worth fixing: the hard part (the foundation, the search infrastructure, the security-gate UX) is already done well.

---

## Verification against the code (0.140.8)

Each claim above was checked against the source at 0.140.7 before acting on it.

| # | Claim | Verdict |
|---|---|---|
| 1 | 30dp / 36dp icon buttons | **Accurate.** `TransactionsScreen.kt` edit/delete were `size(30.dp)`; `CategoriesScreen.kt` edit/delete/overflow were `size(36.dp)`. The review left out that 48dp makes each ledger card's meta row taller. **Fixed in 0.140.8.** |
| 2 | "11sp on every displayed currency amount" | **Wrong.** `currencyFontSize` styles only the trailing ISO code of non-AED amounts (`AmountText.appendMoney`). AED amounts draw the Dirham glyph sized in em, and the numerals use each call site's style (titleMedium and up). `metaFontSize` is used only by `MetaLabel` (17 uses in 6 files, uppercase and tracked), and Material 3's own `labelSmall` is 11sp. **Not changed.** |
| 3 | Insights cards highlight the Plan tab | **Accurate, and worse than described.** Because `goToTab` uses `saveState`/`restoreState`, tapping Insights from there restored the same saved stack, so the tap appeared to do nothing while Plan stayed lit. The suggested fixes (a `fromCrossTabLink` flag, or duplicate `insights/…` routes) weren't needed. **Fixed in 0.140.8:** the highlight comes from the back stack (`TabRouting.tabForStack`), and re-tapping the tab you're in pops to its root (`TabRouting.reselectRootIndex`). |
| 4 | `SettingsScreen.kt` is 1614 lines with 8 screens inside | **Accurate.** Scheduled as a pure refactor. |
| 5 | Only Splits and Copilot skip `MMScaffold` | **Understated.** Nine pushed screens used a raw `Scaffold` + `TopAppBar`: Bills, Goals, Merchant detail, Monthly report, Debt planner, Group detail, Splits, Copilot and Settings' Categories & people. Rules used a raw `Scaffold` with no title or back arrow at all. A raw `Scaffold` also paints the opaque canvas over the app's ambient background. **Fixed in 0.140.9:** all ten use `MMScaffold` (Rules gains "Parsing rules" and a back arrow), and the plain cards on Splits and Copilot use `MMCard`. |
| 6 | Search doesn't say it finds features | **Accurate.** **Fixed in 0.140.8** (hint: "Search transactions, merchants, or jump to a feature…"). |

Priority item 7 (move Splits/Copilot to `MMScaffold`/`MMCard`) is covered by row 5, with the corrected scope.
