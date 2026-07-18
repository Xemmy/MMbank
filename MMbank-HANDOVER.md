# MMbank — Handover (post phased upgrade, Phases 0–6)

*Final state after executing `MMbank-PHASED-UPGRADE-PLAN.md`. Written in Phase 6
on branch `claude/phase-6-completion-mtqgb7`, which carries the complete phase
stack on top of base commit `e363638` ("Add account balance correction").
Last updated: 2026-07-10.*

> **Note on this document's origin:** the upgrade plan referenced an existing
> `MMbank-HANDOVER.md`, but no file by that name was ever committed to the
> repository. Phase 6's "update the handover" therefore *created* this document
> from scratch as the single source of truth for the post-upgrade state.

---

## 1. Repository & branch topology — read before touching anything

- **`main` is stale.** It points at `2717fb6`, far behind the feature work.
  Nothing from the phased upgrade (nor several earlier feature commits) has
  been merged into it.
- **The complete, final state is `claude/phase-6-completion-mtqgb7`.** Its
  first-parent chain is: `main`'s history → `e363638` → `phase-0` (`169bb0b`)
  → phase-1 (`3456133`) → phase-2 (`b5bdc6f`, `5d7778d`) → phase-3 (`ce25407`)
  → phase-4 (`688b3e5`) → phase-5 (`345ad20`…`49b4a0a`) → phase-6 (this
  commit). Merging this one branch into `main` lands everything.
- **Stale branches, safe to delete** (each tip verified as a strict ancestor
  of this branch — deleting them loses nothing):
  `claude/phase-1-continuation-3wjv4j`, `claude/phase-2-continuation-kb84bt`,
  `claude/phase-3-continuation-4p6i26`, `claude/phase-4-start-6s266c`,
  `claude/phase-5-start-mt8j0o`,
  `claude/manual-transactions-rule-accounts-7e51a5` (merged via PR #2),
  `claude/app-battery-optimization-v1w0vn` (merged via PR #1),
  `claude/hu-udv8yb`. Phase 6 attempted the deletion, but the sandbox's push
  credential only allows updating the session's own branch (403 on ref
  deletes), so **this cleanup must be done from the GitHub UI/CLI**.
- **Kept deliberately:** `claude/mmbank-phased-upgrade-plan-4thlrg` (head) and
  `claude/android-expense-tracker-arch-kdcu9b` (base) back the still-open,
  fully superseded **PR #3**. Deleting the head branch would force-close that
  PR, so the pair was left alone. Recommended cleanup: close PR #3 manually,
  then delete both branches. Their only unique commits are the empty PR-merge
  commits `f7b931b` / `ac11061`; all real work is in this branch.

## 2. What CI proves (and what it doesn't)

`.github/workflows/test-build.yml` runs on pushes to `main` and `claude/**`:

1. **Lint & Unit Tests** — `./gradlew :app:testDebugUnitTest --no-daemon -x lint`,
   but the step has `continue-on-error: true`: **the workflow stays green even
   if unit tests fail**, and lint never actually runs (`-x lint`).
2. **Build APK** — `:app:assembleDebug` (gating, with retries) + a non-gating
   `:app:assembleRelease`.
3. **Final Status** — fails only if the APK build failed.

**A green check mark proves compilation only.** To verify tests, open the
"Run unit tests" step output or the `unit-test-results` artifact. Verification
record for the final code-bearing tip `49b4a0a`: workflow run #49
(`29101985550`) — build green **and** the "Run unit tests" step itself
concluded `success` (Gradle exit 0 ⇒ all tests passed). The Phase 6 push
re-ran the full suite on the final state.

There is **no Android SDK in the dev sandbox** and Gradle downloads are
blocked; all verification is CI-only. See `CLAUDE.md`.

## 3. Data layer — schema v9

`data/local/AppDatabase.kt` is at **`version = 9`** with an explicit,
non-destructive migration chain `MIGRATION_1_2` … `MIGRATION_8_9`.

`MIGRATION_8_9` (added in Phase 1):

```sql
ALTER TABLE transactions ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN statementCycleDay INTEGER;            -- nullable
UPDATE accounts SET statementCycleDay = 10
 WHERE LOWER(name) LIKE '%tabby%' OR LOWER(IFNULL(bankName,'')) LIKE '%tabby%';
```

- `Transaction.excludeFromStats: Boolean = false` and
  `Account.statementCycleDay: Int? = null` are mirrored across
  `data/local/entity/Entities.kt`, `domain/model/Models.kt`, and both mapper
  directions, with round-trip coverage in `EntityMappersTest`.
- `statementCycleDay` was deliberately front-loaded in Phase 1 so Phase 4's
  statement-cycle filter needed no second migration. Tabby is seeded with `10`
  via the LIKE match above; all other accounts stay `null`.

> **Accepted gap (unchanged from the plan):** Room migration tests need
> instrumentation and CI runs JVM tests only, so `MIGRATION_8_9` is verified by
> compilation plus SQL review. Do one manual upgrade-install sanity check on a
> device when convenient.

### Analytics-purity flag — the three ingestion points
1. **Balance corrections** (`AccountsScreen.kt`): the generated
   "Balance adjustment" transaction is created with `excludeFromStats = true`.
   The rule-name constant lives in `domain/filter/TransactionFilterEngine.kt`
   (`BALANCE_ADJUSTMENT_RULE_NAME`) and a cross-file test keeps the two in sync.
2. **Tabby refund adjustments** (`IngestCapturedMessageUseCase.kt`): a
   "Transaction adjusted" (decreased/increased by X) capture produces a
   transaction initialized with `excludeFromStats = true`.
3. **Manual control**: `TransactionEditorDialog` has an
   "Exclude from Analytics" switch bound to the field.

## 4. Parsing — signature routing (Phase 2)

`domain/parser/TransactionParser.kt` now runs **two stages**:

1. **Signature routing.** A `CompiledRule` may register
   `signatures: List<String>` — plain-text phrases checked with
   case-insensitive containment. A hit binds the message to that rule's
   account/direction immediately, skipping regex matching for that rule.
   Extraction is delegated to `domain/parser/GenericExtractors.kt`.
2. **Classic regex loop** — unchanged behavior for regex-pattern rules.

Collision & failure semantics (implemented exactly, tested in
`SignatureRoutingTest`):
- Multiple signature hits → **higher priority number wins** (the codebase's
  existing rule-priority convention — see deviation #2 below); priority tie →
  **longest matched signature** wins; still tied → first-registered wins and a
  warning is emitted through the parser's diagnostics sink.
- Signature hit but **no plausible amount** → the rule does **not** consume the
  message; processing falls through to remaining rules, then to the existing
  unparsed-message behavior. A transaction is never fabricated with a guessed
  or zero amount.
- Merchant extraction failure is non-fatal → `null` merchant → lands in
  UNCATEGORIZED → surfaces in the Phase 4 triage view.

`GenericExtractors`:
- **Amount/currency**: one flexible regex covering `AED 46.12`, `AED229.59`,
  `46.12 AED`, and `1,234.56`; restricted currency vocabulary (ISO codes,
  symbols, Arabic abbreviations); "plausible" = currency-adjacent number with
  ≤ 2 decimals; zero/negative rejected; bare numbers without an adjacent
  currency are never plausible.
- **Merchant**: first `" at "`/`" to "` clause up to a sentence/clause/line
  boundary, stripped of trailing status verbs, dates and reference codes;
  fallback to the notification title (the Binance Mastercard shape from
  `99123.jpg`) or a leading vendor label.

Tests are written against the literal `99123.jpg` notification strings
(Tabby limit, Binance Mastercard), plus collision, extraction-failure and
regression categories (`SignatureRoutingTest`, `GenericExtractorsTest`;
existing `TransactionParserTest` still passes).

## 5. Dashboard "copilot" (Phase 3)

- `DashboardViewModel` holds `showAllLedgerTransactions: Boolean = false`.
  Stats and category aggregation filter out `excludeFromStats` rows by
  default; the toggle bypasses the filter for raw-ledger figures.
- A low-profile filter chip on the Dashboard flips the toggle.
- **Audit-friendly feed:** excluded rows stay in the activity feed,
  presentation-only dimmed (`alpha 0.55`) with a slashed-eye icon beside the
  amount. The ledger list itself is never filtered.
- Covered by `DashboardViewModelTest` (both toggle states, category
  aggregation).

## 6. Filtering engine (Phase 4)

`domain/filter/TransactionFilterEngine.kt` — a **pure-Kotlin, Compose-free**
object the Transactions ViewModel delegates to. Filter state is one
`ActivityFilters` value: date range, text query, direction, account, person,
triage flag, min/max amount, source (MANUAL/SMS/NOTIFICATION), analytics
status (Included only / Excluded only).

- **Statement-cycle preset is data-driven:** when the active account filter
  names an account with non-null `statementCycleDay`, the window snaps to
  `[cycleDay of previous month, cycleDay − 1 of current month]`; otherwise it
  falls back to rolling 30 days. No Tabby dates are hardcoded anywhere — a
  future card with a different cycle needs zero code changes.
- Edge cases implemented and tested: cycle day 29/30/31 clamps to a short
  month's last day; year boundary (January window starts in December).
- Other date presets: Today, Past 7 Days, Past 30 Days, Year-to-Date, custom
  twin-calendar Start→End (auto-swapped if reversed).
- **"Inbox Zero" triage:** UNCATEGORIZED rows plus system-generated balance
  adjustments (`needsTriage`).
- Full matrix in `TransactionFilterEngineTest` (21 tests): window math,
  clamping, year boundary, null cycle day, amount range, source, analytics
  status, triage, combined predicates.

## 7. Premium UI pass (Phase 5)

Lives in the same stack (see deviation #3). Components:

- `ui/theme/Glass.kt` — `Modifier.glassSurface()` with the **API 31 gate**
  (`GlassSpec`): blur + translucent surface + hairline border on 31+, more
  opaque solid translucent fallback below. Applied to the transaction editor
  and balance-correction dialogs. Spec math tested in `GlassSpecTest`.
- `ui/util/AmountText.kt` — split amount typography: small light ISO currency
  code, high-contrast tabular numerals (`MoneyPartsTest`).
- `MetaChip` (in `TransactionsScreen.kt`) — tiny letter-spaced uppercase meta
  labels.
- `ui/theme/Glow.kt` — semantic tokens (`GlowTone`/`GlowTones`): desaturated
  mint for credits, soft copper/crimson for debits, gold hint for Binance
  Mastercard origin, soft teal for Tabby (`GlowTonesTest`).
- `ui/screens/transactions/FilterBarCollapse.kt` — pure-Kotlin collapse math
  behind the custom `NestedScrollConnection` that compresses the search field
  and filter chips into a compact top bar on scroll (`FilterBarCollapseTest`);
  `Modifier.animateContentSize()` on transaction/account cards.

> **Outstanding:** visual QA is manual by nature of the sandbox. CI proves the
> UI pass compiles; nobody has signed it off on a device yet. Review on an
> API 31+ device **and** one pre-31 device/emulator (glass fallback) before
> merging to `main`.

## 8. Test inventory

159 `@Test` methods across 17 pure-JVM classes under `app/src/test`, notably:
`TransactionParserTest` (27), `GenericExtractorsTest` (25),
`TransactionFilterEngineTest` (21), `SignatureRoutingTest` (11),
`IngestCapturedMessageUseCaseTest` (10), `EntityMappersTest` (7),
`DashboardViewModelTest` (7), `FilterBarCollapseTest` (7),
`BalanceAdjustmentTest` (4), plus theme/format/backup/cashback suites.

## 9. Deviations from the plan

1. **This handover was created, not updated** — `MMbank-HANDOVER.md` never
   existed in the repository despite the plan referencing it.
2. **Signature-collision tiebreak:** the plan's §2.2 said "lowest priority
   number wins (consistent with the existing rule-priority convention)", but
   the existing convention is the opposite — the parser has always tried rules
   sorted by **descending** priority. The implementation follows the actual
   codebase convention (**higher number wins**), honoring the plan's stated
   rationale over its literal wording. Ties resolve by longest signature, then
   first-registered + warning, exactly as specified.
3. **Branching:** phases were developed as one stacked sequence of `claude/*`
   session branches without merging to `main` between phases; Phase 5 was not
   on a separate `claude/ui-premium-pass` branch off merged `main`. The net
   effect is the same content, but `main` integration is still pending (§10).
4. **Tabby cycle-day seeding** matches on account/bank name (`LIKE '%tabby%'`)
   rather than a fixed row id, since ids aren't stable across installs.
5. Phase 2 commit `5d7778d` aligned the signature tests with the *actual*
   `99123.jpg` notification strings and extended merchant extraction to
   title-based merchants + card suffixes; justification is in that commit
   message per the plan's regression rule.

## 10. Recommended next steps (user actions)

1. Merge `claude/phase-6-completion-mtqgb7` into `main` (PR or fast-forward);
   everything is on that one branch.
2. Do the manual device checks: schema v8→v9 upgrade install, and the Phase 5
   visual pass on API 31+ and pre-31 devices.
3. Delete the stale branches listed in §1 (blocked for the sandbox
   credential); close stale PR #3, then also delete
   `claude/mmbank-phased-upgrade-plan-4thlrg` and
   `claude/android-expense-tracker-arch-kdcu9b`.
4. Deferred (unchanged from the plan's Appendix B): instrumented migration
   tests if the app grows; data-driven signature registry if the signature
   list gets large; multi-currency tests for the generic amount extractor.
