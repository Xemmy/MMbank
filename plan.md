# Plan: customizable screen layout (hide / reorder boxes)

## Context

Home and Insights are fixed stacks of cards hard-coded in one `LazyColumn` each
(`DashboardScreen.kt:146-300`, `InsightsScreen.kt:118-290`). The user wants to
choose which boxes appear and in what order. This plan adds one small, reusable
"screen layout" mechanism and applies it to **Home** first, then **Insights**.

Target version: `0.123.0` / `versionCode 179` (step 1), `0.124.0` / `180` (step 2).

## Design

### 1. Pure layout model — new `domain/layout/ScreenLayout.kt`

- `data class SectionSpec(val id: String, val label: String, val removable: Boolean = true)`
- `data class ScreenLayout(val order: List<String>, val hidden: Set<String>)` with
  - `visibleOrder` — `order` minus `hidden`
  - `move(id, direction)` — delegates to `ReorderList.move`
  - `toggle(id, specs)` — no-op for non-removable sections
- `object ScreenLayoutCodec`
  - `encode(layout): String` — `"a,b,c|x,y"` (order `|` hidden)
  - `decode(raw, defaults: List<SectionSpec>): ScreenLayout` — forgiving:
    blank/garbage → defaults; unknown ids dropped; duplicates dropped; sections
    added in a later app version are inserted after their default-order
    predecessor (so an update never loses a new card); non-removable ids are
    stripped from `hidden`.
- `object HomeSections` / `object InsightsSections` — the id constants and the
  default-ordered `List<SectionSpec>` for each screen.

Reuse: generalise `domain/ReorderList.move` from `List<Long>` to `<T> List<T>`
(source-compatible; existing callers and `ReorderListTest` unchanged).

### 2. Persistence — `data/prefs/AppPreferences.kt`

Two string prefs following the existing pattern: `homeLayoutRaw`
(`KEY_HOME_LAYOUT`) and `insightsLayoutRaw` (`KEY_INSIGHTS_LAYOUT`), default `""`.
No Room change, no migration.

### 3. ViewModel — `ui/screens/dashboard/DashboardViewModel.kt`

Both screens use `DashboardViewModel`, and each layout is only edited from its
own screen, so this is the "plain preference, one ViewModel" toggle shape from
CLAUDE.md (no `AppContainer` flow needed):

- `val homeLayout: StateFlow<ScreenLayout>` and `val insightsLayout`, each a
  `MutableStateFlow` seeded with `ScreenLayoutCodec.decode(prefs.…Raw, defaults)`.
- `moveHomeSection(id, direction)`, `toggleHomeSection(id)`, `resetHomeLayout()`
  (and the Insights equivalents) — update the flow and write the encoded string
  back to prefs.

Kept as separate flows rather than added to the already-full `uiState` combine.

### 4. Home screen — `ui/screens/dashboard/DashboardScreen.kt`

Customizable sections (default order = today's order):

| id | Box | Removable |
|---|---|---|
| `weekly_digest` | Weekly digest | yes |
| `net_worth` | Net worth | yes |
| `financial_health` | Financial health | yes |
| `month_card` | This month | yes |
| `accounts` | Account carousel | yes |
| `cards_due` | Cards due | yes |
| `unassigned` | Unassigned money | **no** (it explains money missing from net worth) — reorder only |
| `crypto` | Crypto header + card | yes |
| `recent` | Recent activity (filter chip + header + feed) | yes |

Pinned, not customizable: capture-health banner, onboarding card, review badge
(alerts that already have their own dismiss/snooze and must stay on top).

Refactor: each section's existing `item { … }` block moves unchanged into a
local `LazyListScope` extension (e.g. `fun LazyListScope.netWorthSection(…)`),
keeping its current data-availability condition and prefixed keys. The
`LazyColumn` body becomes: pinned alerts, then

```kotlin
layout.visibleOrder.forEach { id -> when (id) { HomeSections.NET_WORTH -> netWorthSection(…) … } }
```

then a footer `item(key = "customize")` with a low-profile "Customize Home"
text button. The footer is always present, so a fully-hidden screen is always
recoverable.

Edge: hiding `accounts` while an account filter is selected → the ViewModel
clears `selectedAccountId` in `toggleHomeSection` so the feed isn't silently
filtered with no visible control.

### 5. Editor sheet — new `ui/components/CustomizeLayoutSheet.kt`

One reusable sheet for both screens, modelled on `ManageAccountsBottomSheet.kt`
(same `MMBottomSheet`, same "Drag the ≡ grip to reorder" hint):

- One row per section in `layout.order`: `ReorderHandle` + `liftWhileDragging`
  (`ui/designsystem/ReorderHandle.kt`, includes TalkBack move actions), the
  label, and a `Switch` for show/hide (disabled with "Always shown" caption for
  non-removable sections). Hidden rows are dimmed but stay reorderable.
- "Reset to default" text button.
- Changes apply live to the screen behind the sheet.

### 6. Insights screen (step 2) — `ui/screens/insights/InsightsScreen.kt`

Same treatment. Pinned at top: ledger toggle, date filter row. Customizable:
`period_tiles`, `insights_card`, `net_worth_trend`, `goal_card`,
`category_budgets_card`, `cashflow_card`, `category_card`, `person_card`, and
the shortcut rows (`analytics_row` … `rewards_row`) each as its own section.
The "Charts appear here once transactions are captured" placeholder shows when
no data and at least one chart section is visible. Footer "Customize Insights".

## Files

- New: `domain/layout/ScreenLayout.kt`, `ui/components/CustomizeLayoutSheet.kt`
- Modified: `domain/ReorderList.kt`, `data/prefs/AppPreferences.kt`,
  `ui/screens/dashboard/DashboardViewModel.kt`,
  `ui/screens/dashboard/DashboardScreen.kt`,
  `ui/screens/insights/InsightsScreen.kt`, `app/build.gradle.kts`,
  `MMbank-IMPROVEMENT-PLAN.md` (✅ with version), `MMbank-FEATURE-LOG.md`
- New tests: `app/src/test/java/com/xemmy/mmbank/domain/layout/ScreenLayoutTest.kt`

## Delivery steps (one commit + CI run each)

1. Model + codec + prefs + ViewModel + editor sheet + Home wiring → 0.123.0.
2. Insights wiring → 0.124.0.

## Out of scope

- Including the layout in encrypted backups (would need a backup format bump;
  can follow later using the nullable-`extras` pattern in `BackupManager.kt`).
- Customizing Activity / Plan / Accounts tabs, card resizing, or multi-column
  layouts.

## Verification

Pure-JVM tests in `ScreenLayoutTest` (run by CI's `:app:testDebugUnitTest`):

- encode → decode round-trip
- blank / garbage / duplicate / unknown-id input falls back safely
- a section new in defaults is inserted after its default predecessor
- non-removable id can't be hidden (via `toggle` and via stored string)
- `move` at the ends is a no-op; `visibleOrder` excludes hidden
- `HomeSections` / `InsightsSections` default ids are unique
- `ReorderListTest` still passes after the generic change

Push to `claude/mmbank-local-ai-parsing-4cfi14`, wait for the workflow to
finish (tests + APK build green) before the next push.

**Not covered by CI (device check, to list in the feature log):** the sheet's
look, drag feel and lift animation, live re-ordering of the list behind the
sheet, layout surviving an app restart, and the "Customize" footer placement.
