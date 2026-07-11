# Phase 5 (Final) — "Obsidian Ledger" UI Spec

*Replaces the Phase 5 section of `MMbank-PHASED-UPGRADE-PLAN.md`. Design direction locked after mockup review: **dark glass canvas + per-account edge glows (A) + monospaced tabular amounts with dotted leader lines (B) + floating day pills (C) + real blur reserved for dialogs (A)**. A visual mockup (`design/mmbank-final-design.html`) was referenced when this spec was authored but was not available to commit alongside it; this document's literal token values (hex colors, sizes, spacing) are the source of truth for implementation.*

**Branch:** `claude/ui-premium-pass`, created from merged main after Phases 1–4.
**Scope:** `TransactionsScreen.kt`, `AccountsScreen.kt`, `InsightsScreen.kt`, `ui/theme/`, shared row/dialog composables. No data, parser, ViewModel-logic, or filter changes — presentation only.

---

## 5.1 Design tokens (define once in `ui/theme/`, never inline)

### Color — semantic names, dark canvas
| Token | Value | Use |
|---|---|---|
| `canvas` | `#0B0E14` | Screen background |
| `surfaceGlass` | `White @ 5.5%` | Row cards, search field, chips |
| `hairline` | `White @ 9%` | All borders (12% on dialogs) |
| `textPrimary` | `#EDF1F6` | Merchant names, titles |
| `textMeta` | `textPrimary @ 48%` | Meta rows, labels, placeholders |
| `credit` | `#86E3B8` | Credit amounts (desaturated mint) |
| `debit` | `#E0906F` | Debit amounts (soft copper) |
| `glowBinance` | `#F0BE5A @ 28% → transparent` | Edge glow |
| `glowTabby` | `#2DD4BF @ 26% → transparent` | Edge glow |
| `glowSystem` | `#96A0BE @ 18% → transparent` | Edge glow (manual/system accounts) |
| `dialogPanel` | `#12161F @ 72%` (blur path) / `#151A24 @ 96%` (fallback) | Glass dialogs |
| `scrim` | `Black @ 35–45%` | Behind dialogs, both paths |

Ambient canvas wash: three static radial gradients drawn once behind the `LazyColumn` — teal `#2DD4BF @ 13%` top-left, gold `#F0BE5A @ 10%` right-center, indigo `#788CFF @ 7%` bottom-center.

### Type — three roles
| Role | Face | Notes |
|---|---|---|
| Display / titles / avatar initials | **Space Grotesk** SemiBold | Screen titles 21sp, `-0.01em` tracking |
| Body | **Inter** | Merchant 13sp Medium |
| Amounts & numeric fields | **IBM Plex Mono** SemiBold | 14.5sp, signed (`−` / `+`), `FontFeatureSettings("tnum")`, tabular alignment across rows |
| Meta / labels (Inter) | 8.5–9sp, uppercase, `+0.14em` tracking, `textMeta` | Category · Account rows, field labels |

Currency treatment everywhere an amount appears: `AED` at 8.5sp, Light, uppercase, `+0.1em` tracking, 55% opacity, baseline-aligned before the numerals.

Bundle both webfonts' Android equivalents in `res/font/` (Space Grotesk, IBM Plex Mono are on Google Fonts; Inter may already be present).

### Shape
Row cards 18dp radius · dialogs/sheets 24dp · chips & day pills fully rounded (99dp) · avatar tiles 34dp square, 11dp radius.

---

## 5.2 Blur policy — the load-bearing rule

1. **List rows never blur.** A row is: `surfaceGlass` background + `hairline` border + edge glow. This keeps `LazyColumn` scrolling cheap on every device.
2. **Dialogs and sheets get real glass** via a shared `Modifier.glassSurface()`:
   - **API ≥ 31:** `dialogPanel` @ 72% + backdrop blur ~22dp (RenderEffect), `hairline` @ 12% border, 24dp radius.
   - **API < 31:** identical layout with the solid ~96% `dialogPanel` fallback — `Modifier.blur()` silently no-ops below 31, so the gate must be explicit (`Build.VERSION.SDK_INT >= 31`).
   - The scrim ships on **both** paths.
3. Apply `glassSurface()` to: `TransactionEditorDialog`, the balance-correction prompt, and any bottom sheets on the three screens.

---

## 5.3 Components

**Transaction row** (shared composable, used by feed on all screens):
`[avatar 34dp] [merchant + meta column] [dotted leader, weight 1f] [amount block]`
- Avatar: `surfaceGlass` tile, brand-tinted Space Grotesk initials (gold Binance, teal Tabby, slate system).
- Edge glow: `drawBehind` radial gradient centered on the card's left edge, ~110dp radius, brand glow token → transparent, clipped to card shape. No bitmaps, no blur.
- Dotted leader: `drawBehind` 1.5dp dotted line (`PathEffect.dashPathEffect` or spaced circles), `White @ 20%`, baseline-aligned between the text block and amount, min 12dp wide. On overflow, merchant ellipsizes first — the leader never disappears entirely.
- Amount block: `AED` micro-label + signed mono numerals in `credit`/`debit`.
- **Excluded rows** (from Phase 3 — restyle only): whole card `alpha(0.55f)`, 12dp slashed-eye icon at 70% before the amount block. Logic untouched.

**Floating day pills:** `stickyHeader` items in the `LazyColumn` — `White @ 8%` pill, `hairline` border, 9sp uppercase `+0.18em` label ("TODAY · 10 JUL"). They float over content while their section scrolls.

**Filter chips & search:** glass pills — idle `White @ 4%` bg / `hairline` border / `textMeta` label; active `White @ 12%` bg / `White @ 28%` border / white label. Search field is a `surfaceGlass` 14dp-radius bar.

**Editor dialog** (see mockup screen 2): `glassSurface()` panel; uppercase micro field labels; amount field in mono; account row with a glowing 7dp brand dot; category chip row (active = teal-tinted); divider; the Phase 1 "Exclude from Analytics" switch row with helper text *"Kept in ledger, hidden from stats & charts"* (switch track = `credit` mint when on); trailing buttons — text "Cancel", filled `#EDF1F6`-on-`canvas` "Save changes".

**Motion (unchanged from original Phase 5):** `NestedScrollConnection` collapses search + chips into a compact top bar on downward scroll; `animateContentSize()` on list items and expandable cards; keep durations ≤ 250ms, respect the system reduced-motion/animator-scale settings by keying off `Settings.Global.ANIMATOR_DURATION_SCALE` where practical.

**Other screens:** `AccountsScreen` cards adopt the same surface/hairline/glow language (glow = that account's brand token); `InsightsScreen` charts recolor to `credit`/`debit`/brand tokens on `canvas` — no chart-logic changes.

---

## 5.4 Verification

**CI proves compilation only.** Visual sign-off checklist (you, on device):
1. Feed scrolls smoothly with 200+ rows (no per-row blur regressions).
2. Edge glows are visible but faint; correct brand color per account.
3. Amounts align vertically across rows (tabular figures working).
4. Dotted leaders render at every row width; ellipsizing behaves.
5. Day pills stick and float over scrolling content.
6. Editor dialog: real blur on an API 31+ device; clean solid fallback on one pre-31 device/emulator.
7. Excluded rows read dimmed + slashed-eye in both the feed and after filter changes.

Commit granularity: tokens/theme → row composable → day pills/chips/top bar → dialogs → Accounts/Insights recolor. One visual concern per commit so on-device review is incremental.
