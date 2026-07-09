# MMbank — Phased Upgrade Plan & Claude Code Execution Spec

*Supersedes the single-document "Comprehensive Upgrade & Feature Handover." Same scope, resequenced into six gated phases with per-phase Claude Code session instructions, mandatory tests, and CI verification gates. All work builds on the state in `MMbank-HANDOVER.md`, branch `claude/manual-transactions-rule-accounts-7e51a5` @ commit `e363638`.*

---

## 0. Ground Rules — read first, applies to every phase

### Environment constraints (unchanged, critical)
- **Zero local compilation.** The dev sandbox has no Android SDK and Gradle downloads are blocked (403). Never attempt `gradle build`, `gradle test`, or app execution locally.
- **Verification = CI only.** Write precise code, push to the remote branch, and rely on GitHub Actions (`.github/workflows/test-build.yml`) for linting, JVM unit tests, and compilation.
- Consequence: keep diffs small and lean heavily on **pure-JVM unit tests** — they are the only executable safety net.

### Phase gating
- **One phase per Claude Code session.** Do not begin Phase N+1 until Phase N's CI run is green.
- Commit messages prefixed `phase-N:`. Push after each logical unit of work, not only at phase end, so a broken commit is easy to isolate in CI history.
- If CI fails, fix it inside the same phase before moving on. Never "fix it later."
- Phases 1 → 2 → 3 have hard dependencies (schema → parser → dashboard). Phases 4 and 5 depend on 1 but not on each other. Phase 5 (UI) is deliberately last and lives on its own branch.

### Claude Code workflow (repeat for every phase)
1. Open a fresh session in the repo root (or `/clear` an existing one — each phase should start with clean context).
2. Switch to **Plan Mode** (Shift+Tab cycles permission modes; confirm the status line shows plan mode) and paste the phase kickoff prompt from **Appendix A**.
3. **Review the plan before approving.** Check the proposed file list against the "Files touched" list for the phase. Reject anything outside phase scope — scope creep across phases is the main failure mode this document exists to prevent.
4. Approve, let Claude implement, then commit and push.
5. Watch the CI run (`gh run watch` if the GitHub CLI is available in the sandbox; otherwise the repo's Actions tab). Paste any failure logs back into the same session and iterate until green.
6. End the session. Next phase = next session.

### CLAUDE.md — commit this in Phase 0
A project `CLAUDE.md` gives every session the environment constraints without re-explaining them. Suggested content:

```markdown
# MMbank
Android personal-finance app. Kotlin, Jetpack Compose, Room, MVVM.

## Environment — CRITICAL
- NO Android SDK in this sandbox. Gradle downloads are blocked (403).
  NEVER run gradle build/test/run locally.
- Verification happens ONLY via GitHub Actions
  (.github/workflows/test-build.yml) after pushing to the remote branch.
- Workflow: write code + pure-JVM unit tests -> push -> read CI results.

## Conventions
- Room schema changes: bump `version` in data/local/AppDatabase.kt and add an
  explicit Migration object with raw SQL. Never use destructive migration.
- Domain models (domain/model/Models.kt) mirror entities
  (data/local/entity/Entities.kt) via toEntity()/toDomain() mappers.
  Any field added to one must be added to all three, with a mapper
  round-trip unit test.
- Every new piece of logic gets a pure-JVM unit test in the same commit.
- Work one phase at a time per MMbank-PHASED-UPGRADE-PLAN.md. Do not touch
  files outside the active phase's "Files touched" list.
```

---

## Phase 0 — Session Setup & Guardrails (~15 min)

**Goal:** repo is prepared for gated multi-session work; no feature code.

**Tasks**
1. Add the `CLAUDE.md` above at the repo root.
2. Add this document as `MMbank-PHASED-UPGRADE-PLAN.md` at the repo root (the kickoff prompts reference it by name).
3. Read `.github/workflows/test-build.yml` and record in `CLAUDE.md` exactly which Gradle tasks CI runs (e.g. `lint`, `testDebugUnitTest`, `assembleDebug`) so later phases know what "green" proves.
4. Skim `MMbank-HANDOVER.md` to confirm the base state matches commit `e363638`.

**Definition of done:** both files committed and pushed; CI run on the push is green.

---

## Phase 1 — Analytics Purity Flag (data + ingestion layer)

**Goal:** balance corrections and Tabby refund adjustments stop skewing analytics, via a non-destructive `excludeFromStats` flag, while remaining visible in the raw ledger.

**Files touched**
- `data/local/entity/Entities.kt`
- `domain/model/Models.kt` + mapper extensions (`toEntity()` / `toDomain()`)
- `data/local/AppDatabase.kt`
- `ui/screens/accounts/AccountsScreen.kt`
- `domain/usecase/IngestCapturedMessageUseCase.kt`
- `ui/screens/transactions/TransactionsScreen.kt` (`TransactionEditorDialog` only)
- Test sources

### 1.1 Schema v8 → v9 (one migration, two columns)
- `TransactionEntity`: append `excludeFromStats: Boolean = false`.
- `AccountEntity`: append `statementCycleDay: Int? = null`. **This is deliberately front-loaded for Phase 4** (statement-cycle date presets) so the app needs only one migration instead of two. Seed the Tabby account with `10`; leave all others `null`.
- `AppDatabase.kt`: `version = 9`, plus `MIGRATION_8_9` executing explicit SQL:
  - `ALTER TABLE transactions ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0`
  - `ALTER TABLE accounts ADD COLUMN statementCycleDay INTEGER` (nullable, no default)
  - (Adjust table/column names to the actual `@Entity` definitions — verify before writing the SQL.)
- Mirror both fields in the domain models and both mapper directions.

> **Known gap, accepted:** Room migration tests require instrumentation and CI only runs JVM tests, so `MIGRATION_8_9` is verified by compilation plus careful SQL review. Flag this in the PR description; do one manual upgrade-install sanity check on a device when convenient.

### 1.2 Automatic flagging (three ingestion points)
1. **Balance corrections** — in the `AccountsScreen.kt` balance tune/adjust dialog, the generated "Balance adjustment" transaction is created with `excludeFromStats = true`, hardcoded.
2. **Tabby adjustments** — in `IngestCapturedMessageUseCase.kt`, when a priority-20 "Transaction adjusted" rule fires (decreased/increased by X), the resulting transaction is initialized with `excludeFromStats = true`.
3. **Manual control** — `TransactionEditorDialog` gains a Material 3 `Switch` row labeled **"Exclude from Analytics"**, bound to the field, for internal transfers and cash refunds.

### 1.3 Tests (same commits as the code)
- Mapper round-trip test: `Transaction -> Entity -> Transaction` preserves `excludeFromStats` (both values) and `statementCycleDay` (null and non-null).
- `IngestCapturedMessageUseCaseTest`: feed text mirroring the Tabby "Transaction adjusted" signatures; assert the produced transaction has `excludeFromStats == true`. Add a control case (normal purchase text) asserting `false`.
- A test around whatever function constructs the balance-adjustment transaction, asserting the flag is set.

**Definition of done:** CI green; all three ingestion points covered by at least one assertion each; no analytics code touched yet (that's Phase 3).

---

## Phase 2 — Decoupled Signature-Based Parsing Engine

**Goal:** move rule matching from fragile monolithic regexes to resilient signature routing with generic fallback extraction, per the notification structures in the reference file `99123.jpg`. **This is the riskiest phase — it has the strictest test requirements.**

**Files touched**
- `domain/parser/` (rule model, compilation and execution loops)
- `TextNormalizer` or a new `GenericExtractors` in the same package
- Test sources

### 2.1 Signature identification
- A rule may register `signatures: List<String>` — plain-text phrases checked with case-insensitive containment — instead of (or in addition to) a regex capture pattern.
- If incoming raw text contains a signature (e.g. `"Your available Tabby Card limit"`, `"Binance Mastercard"` as seen in `99123.jpg`), bind the message to that rule's Account and Direction immediately, skipping regex matching for that rule.

### 2.2 Collision & failure rules (must be implemented exactly — do not leave ambiguous)
1. **Multiple signature rules match:** lowest priority number wins (consistent with the existing rule-priority convention). Priority tie → the rule whose matched signature string is **longest** wins (more specific beats more generic). Still tied → first-registered wins, and log a warning so the rule set can be cleaned up.
2. **Signature matches but extraction fails:** if the generic extractors (2.3) cannot recover an **amount**, the rule does **not** consume the message. Processing falls through to the remaining rules (including classic regex rules). If nothing consumes it, the existing unparsed-message behavior applies unchanged. **Never fabricate a transaction with a guessed or zero amount.**
3. Merchant extraction failing is non-fatal: proceed with an empty/`null` merchant, which naturally lands the transaction in UNCATEGORIZED and therefore in the Phase 4 triage view.

### 2.3 Generic fallback extractors
- **Amount & currency:** one shared, flexible regex handling spaced and non-spaced forms — `AED 46.12` and `AED229.59` (both present in `99123.jpg`) — plus trailing-currency (`46.12 AED`) and thousands separators (`1,234.56`). Return the first plausible match; define "plausible" as currency-adjacent number with ≤ 2 decimals.
- **Merchant scraper:** take text trailing `" at "` / `"At "` / `" to "` up to the next sentence or line boundary; strip trailing punctuation, dates, and reference codes. Fallback: prominent app header / vendor label at the start of the notification.

### 2.4 Tests — mandatory, written against the literal `99123.jpg` strings
Create `SignatureRoutingTest` (or extend the existing parser suite):
- Tabby limit notification (verbatim text) → routes to the Tabby rule, correct account/direction, amount parsed from the **non-spaced** `AED229.59` form.
- Binance Mastercard notification (verbatim text) → routes correctly, amount parsed from the **spaced** `AED 46.12` form, merchant extracted from the `at …` clause.
- Collision case: synthetic text containing two registered signatures → the priority/length tiebreak selects the expected rule.
- Extraction-failure case: text containing a signature but no parseable amount → message is **not** consumed by the signature rule and falls through.
- **Regression:** the entire existing regex-rule test suite still passes unmodified. If any existing test needs changing, treat that as a design smell and justify it explicitly in the commit message.

**Definition of done:** CI green; all five test categories above present; existing parser tests untouched or their changes justified.

---

## Phase 3 — Dashboard "Copilot" Integration

**Goal:** analytics math actually consumes the Phase 1 flag; the ledger stays audit-friendly.

**Files touched**
- `DashboardViewModel.kt`
- Dashboard screen composables (toggle chip)
- Transaction feed card composable (dimming + icon)
- Test sources

### Implementation
- `DashboardViewModel`: add state `showAllLedgerTransactions: Boolean = false`.
- Rewrite `computeStats()` and the category-spending aggregation: by default filter out every transaction where `excludeFromStats == true`; when the toggle is `true`, bypass the filter and compute raw ledger figures.
- UI: a low-profile filter chip / icon toggle at the top of the Dashboard bound to that state.
- **Audit-friendly feed:** excluded transactions remain in the main activity feed. When `excludeFromStats == true`, apply `.alpha(0.55f)` to the card and append a minimalist slashed-eye (⌀-style) icon beside the amount.

### Tests
- `DashboardViewModelTest`: given a mixed list (flagged + unflagged), assert income/spend/net **omit** flagged rows when the toggle is off and **include** them when on.
- Category aggregation test: a flagged transaction's category contributes nothing by default.

**Definition of done:** CI green; both toggle states covered by assertions; feed changes are presentation-only (no filtering of the ledger list itself).

---

## Phase 4 — Advanced & Customized Filtering Engine ("Smart Triage & Statement")

**Goal:** overhaul the in-memory filtering layer in `TransactionsScreen.kt` + its ViewModel.

**Files touched**
- `TransactionsScreen.kt` + associated ViewModel
- A new pure-Kotlin filter/predicate class (see testing note)
- Test sources

### 4.1 Flexible date filtering
- **Statement-cycle preset (data-driven, not hardcoded):** when the active account filter is an account whose `statementCycleDay` (from Phase 1's schema) is non-null, snap the window to `[cycleDay of previous month, cycleDay − 1 of current month]`. Tabby ships with `10` → 10th prev month to 9th current month, exactly as originally specified — but a future card with a different cycle needs zero code changes. If no account filter is active, or the account's cycle day is `null`, default to a rolling 30 days.
- **Edge cases to implement and test:** cycle day 29/30/31 in short months clamps to the month's last day; year boundary (January window starts in December).
- **Custom & relative ranges:** twin-calendar date picker for arbitrary Start→End, plus presets "Past 7 Days", "Past 30 Days", "Year-to-Date".

### 4.2 Extended filters
- **"Inbox Zero" triage chip:** isolates transactions that are UNCATEGORIZED **or** system-generated balance adjustments needing review.
- **Amount threshold/range:** `> X`, `< X`, or bounded `[min, max]`.
- **Source filter:** MANUAL / SMS / NOTIFICATION.
- **Analytics status filter:** "Included Only" vs "Excluded Only" (consumes the Phase 1 flag).

### 4.3 Testing note — extract before you test
Filtering logic must live in a **pure-Kotlin, Compose-free class** (e.g. `TransactionFilterEngine`) that takes a filter config + transaction list and returns the filtered list. The ViewModel delegates to it. This is what makes JVM testing possible.
- Date-window math tests: normal month, clamped short month (cycle day 31 → Feb), year boundary, null cycle day → rolling 30.
- Amount range, source, analytics-status, and triage predicates individually + combined.

**Definition of done:** CI green; `TransactionFilterEngine` (or equivalent) exists with the full test matrix above; no hardcoded Tabby dates anywhere.

---

## Phase 5 — Premium UI/UX Pass (separate branch, device-verified)

**Goal:** the visual makeover across `TransactionsScreen.kt`, `AccountsScreen.kt`, `InsightsScreen.kt`.

**Why it's last and isolated:** CI can only prove this **compiles**. Blur behavior, motion, color, and typography cannot be verified in the sandbox at all — a green build proves nothing visually. Branch off (e.g. `claude/ui-premium-pass`) only after Phases 1–4 are merged, and plan to review every change on a real device or emulator yourself before merging.

### Spec
- **Depth & glassmorphism — with an API gate:** `Modifier.blur()` relies on `RenderEffect` and only works on **API 31+**; on older devices it silently does nothing. Build a shared `glassSurface()` modifier: on API 31+ → blur + translucent surface + ultra-low-opacity hairline border; below 31 → a slightly more opaque solid translucent surface with the same border. Apply to major overlays (`TransactionEditorDialog`, balance-correction prompts).
- **Typography scaling:** currency ISO code (AED) in a distinctly smaller, lighter weight; numerals in a high-contrast face with geometric tracking.
- **Editorial meta rows:** `MetaChip` labels (categories, accounts, metadata) as tiny, letter-spaced uppercase.
- **Contextual glow palette:** desaturated mint for credits, soft copper/crimson for debits; faint low-saturation background brand glows for account origin (gold hint → Binance Mastercard, soft teal → Tabby). Define these as semantic tokens in the theme, not inline hex.
- **Fluid motion:** custom `NestedScrollConnection` on the activity feed that compresses/crossfades the search field and filter chips into a compact top app bar on scroll; `Modifier.animateContentSize()` on transaction lists and expandable cards so filter changes glide.

**Definition of done:** CI compiles green **and** you have visually signed off on a device — API 31+ *and* one pre-31 device/emulator if available (for the glass fallback).

---

## Phase 6 — Wrap-Up
- Re-run the full CI suite on the final merged state.
- Update `MMbank-HANDOVER.md`: new schema version, signature-routing architecture, filter engine, the accepted migration-testing gap, and any deviations from this plan.
- Delete or archive stale branches.

---

## Appendix A — Kickoff prompts (paste one per session, in Plan Mode)

**Phase 0**
> Read CLAUDE.md conventions in MMbank-PHASED-UPGRADE-PLAN.md, Ground Rules + Phase 0. Create the repo CLAUDE.md exactly as specified, inspect .github/workflows/test-build.yml and record which Gradle tasks CI runs into CLAUDE.md, then commit and push. Make no other changes. Show me the plan first.

**Phase 1**
> Read MMbank-PHASED-UPGRADE-PLAN.md, Phase 1 only. Implement the v8→v9 Room migration (excludeFromStats on transactions, statementCycleDay on accounts), mirror both fields through the domain model and mappers, wire the three ingestion points, add the editor switch, and write every test listed in 1.3. Verify actual table/column names from the entity annotations before writing migration SQL. Touch nothing outside the Phase 1 file list. Plan first; wait for my approval.

**Phase 2**
> Read MMbank-PHASED-UPGRADE-PLAN.md, Phase 2 only. Implement signature-based routing in domain/parser/ with the exact collision and extraction-failure rules in 2.2, add the generic amount/currency and merchant extractors in 2.3, and write the full test set in 2.4 using the literal notification strings referenced from 99123.jpg. The existing regex-rule tests must pass unmodified. Plan first; I want to see the proposed rule-matching flow before you write code.

**Phase 3**
> Read MMbank-PHASED-UPGRADE-PLAN.md, Phase 3 only. Add showAllLedgerTransactions to DashboardViewModel, make computeStats() and category aggregation respect excludeFromStats per the spec, add the dashboard toggle chip, and dim flagged cards in the feed (alpha 0.55 + slashed-eye icon) without removing them. Write the DashboardViewModelTest cases in the spec. Plan first.

**Phase 4**
> Read MMbank-PHASED-UPGRADE-PLAN.md, Phase 4 only. Extract a pure-Kotlin TransactionFilterEngine, implement the statement-cycle preset driven by Account.statementCycleDay (no hardcoded dates), the twin-calendar and relative presets, the Inbox Zero chip, amount range, source, and analytics-status filters. Implement and test the short-month and year-boundary edge cases. Plan first.

**Phase 5** *(new branch)*
> Read MMbank-PHASED-UPGRADE-PLAN.md, Phase 5 only. Create branch claude/ui-premium-pass from the merged main state. Implement the glassSurface() modifier with the API 31 gate, typography scaling, MetaChip styling, semantic glow palette tokens, the collapsing top bar via NestedScrollConnection, and animateContentSize on lists. Remember CI only proves compilation — keep each visual change in its own commit so I can review them on-device incrementally. Plan first.

---

## Appendix B — Known gaps & deferred items
1. **Room migration not executable in CI** (JVM-only tests). Mitigated by explicit SQL review + one manual device upgrade check. Consider adding an instrumented-test CI job later if this app grows.
2. **Visual QA is manual** for Phase 5 by nature of the sandbox.
3. **Signature registry growth:** if the signature list gets large, consider moving signatures from code into a data-driven table alongside the existing rules — out of scope for now.
4. **Multi-currency:** the generic amount extractor is written around AED examples; the regex should already tolerate other ISO codes, but no tests cover them yet.
