# MMbank — Old unassigned transactions and persons, without breaking corrected balances

## Context

A balance in MMbank is derived: `openingBalance + every transaction filed in the account` (`AccountBalanceCalculator`). **Update balance** (0.101.0) posts one `ADJUSTMENT` row dated *now*, so the balance is right as of that moment.

Old transactions that are still unassigned are already inside that corrected figure. Filing them afterwards counts them a second time, and the balance moves away from the bank's. Every filing path does this today, with no compensation:

- single assign (`TransactionRepositoryImpl.assignAccount`) and the editor's account change (`update`);
- bulk assign (`bulkAssignAccount`);
- "It's my existing account" on an unknown-ending suggestion (`AccountsViewModel.linkSuggestion`);
- the automatic backfills: merchant learning (`MerchantAccountRepositoryImpl.learnMapping`) and rule assignment (`assignAccountForRule`). These are the worst case, because one tap files many old rows silently.

Setting a **person** never touches a balance; that side is only a tidy-up.

## Decisions (your answers)

| Question | Decision |
|---|---|
| Filing an old row into a corrected account | **Keep the balance.** No prompt. |
| What "old" means | Dated before the account's **last balance update**: its newest `ADJUSTMENT` row, or the account's `createdAt` when it has none. |
| Old rows you can't or won't sort | **Mark as history only**: still searchable and still in spending reports, but no longer reported as unassigned. |
| Persons | **One-tap fill** for old rows that have no person, with a preview count. |
| Active people | Mark people active or inactive; **only active ones appear when assigning**. Filters still list everyone. |

## Ideas considered

1. **Balance-neutral filing — chosen.** When a row dated before the cutoff is filed, shift the account's opening balance by the opposite amount in the same database transaction. Today's balance is unchanged, the history fills in, and no balance formula changes anywhere. Retention already folds rows into the opening balance the same way (`PrunedBalanceFolder`), so this is an existing pattern.
2. **A per-row "don't count in balance" flag — rejected.** Every consumer of the ledger (calculator, timeline, integrity, net worth, balance history, backup) would have to honour it; each one is a place to get it wrong.
3. **Re-run Update balance after filing — rejected.** It is today's behaviour: the balance is wrong until you notice, and each round adds another adjustment row.
4. **Delete old unassigned rows — rejected.** It removes real spending from reports.
5. **History only for leftovers — chosen.** Unassigned rows are in no balance already, so the flag only has to silence the "unassigned" warnings.
6. **Owner per account for persons — not now.** You chose the one-tap fill; this can follow later if wanted.

## Design

### Phase 1 — Balance-neutral filing (0.105.0, versionCode 161, no schema change)

**New pure object `domain/account/BalanceNeutralFiling.kt`.**

- `cutoffFor(account, lastAdjustmentAtMillis)` → the newer of the two.
- `offsets(moves, accounts, cutoffs)` → opening-balance delta per account. A move is a transaction with its old and new `accountId`.
  - Row filed **into** an account and dated before that account's cutoff → opening balance shifts by the opposite of the row's signed amount.
  - Row moved **out of** an account and dated before its cutoff → the mirror shift, so a wrongly filed old row can be moved without breaking the account it leaves.
  - Rows dated on or after the cutoff are untouched: they are not in the corrected figure, so they must move the balance.
  - The signed amount comes from the same code `AccountBalanceCalculator` uses (including `LedgerFx`), so the offset can never disagree with the balance.

**One apply point.** A private helper in `TransactionRepositoryImpl` runs inside the existing `database.withTransaction` of each path: `assignAccount`, `bulkAssignAccount`, `assignAccountForRule`, `update` (when `accountId` changed), plus `MerchantAccountRepositoryImpl.learnMapping`. It reads the affected accounts, a new DAO query for the last `ADJUSTMENT` timestamp per account, applies the offsets, and writes one change-log row per account through `AccountChangeRecorder`: type `OPENING_BALANCE_CHANGED`, new source `AccountChangeSource.HISTORY_FILING`, note "Balance kept: N older transactions filed".

**What you see.**

- A message after filing: "Filed 12 older transactions. Balance kept at AED 4,200.00."
- Account history (`AccountTimeline` / `AccountChangeWording`): one line, "12 older transactions filed — balance kept".
- The unknown-ending card's "It's my existing account" states the same before you confirm.

"Add as new account" from a suggestion is unchanged: `OpeningBalanceSolver` already reconciles a new account with the bank's printed balance.

### Phase 2 — History only (0.106.0, versionCode 162, schema v37)

**New column** `transactions.historyOnly INTEGER NOT NULL DEFAULT 0`. `MIGRATION_36_37` in raw SQL (`MigrationSql.kt`), `version` bump in `AppDatabase.kt`, entity + domain model + mappers, backup export/import.

**Meaning:** "this happened before I started tracking". Only valid on a row with no account. Such a row:

- leaves the Unassigned card (Accounts), the dashboard row, the Integrity `UNASSIGNED` issue, and the unknown-ending suggestions;
- is skipped by the automatic merchant and rule backfills;
- stays in Activity, search and spending reports;
- is cleared automatically when you file the row into an account (which then goes through Phase 1).

**Pure logic** in `AccountBalanceCalculator.unassignedTotals/unassignedCount`, `IntegrityChecker`, `UnknownAccountSuggestions` and `TransactionFilterEngine` (new `AccountFilter.HistoryOnly`; `Unassigned` excludes history-only rows).

**How you mark rows.**

- Unassigned card → **"Before I started tracking…"**: a date (default: the oldest last-balance-update among your accounts) and a live count, "148 transactions before 3 Nov 2026".
- Activity bulk selection → **Mark as history only**.
- Transaction detail shows "History only" with **Undo**.

### Phase 3 — Fill old rows with no person (0.107.0, versionCode 163)

- New pure `domain/ledger/PersonBackfill.kt`: selects rows with a blank person (respecting demo mode).
- Settings → Persons: "212 transactions have no person" → **Assign to…** picks a saved person (default person preselected), shows the count, then calls the existing `bulkSetPerson`.
- No balance effect, so no log row.

### Phase 4 — Active people (0.108.0, versionCode 164, no schema change)

Persons are a saved name list in `AppPreferences.persons`; one list (`TransactionsUiState.knownPersons`) feeds both the assignment menus and the Activity filter today.

- **Storage:** new `AppPreferences.inactivePersons` (same newline format as `persons`), included in backup export/import (`BackupManager`) and in the demo-mode swap (`AppContainer`). Rename, merge and delete in `SettingsViewModel` keep it in step.
- **New pure `domain/ledger/PersonLists.kt`:**
  - `assignable(saved, inactive, default, current)` → active names only, sorted, case-insensitive dedupe. A row that already carries an inactive person still shows that name in its own menu, so opening it never loses the value.
  - `filterable(saved, default)` → everyone, so old transactions of an inactive person remain findable.
- **Settings → People:** an Active switch on each person, with inactive ones grouped below under "Inactive". The default person can't be made inactive; the switch explains why.
- **Where only active people show:** the transaction row and detail person menus, the editor dialog, bulk "Set person", the review card, and the Phase 3 "Assign to…" picker.
- **Where everyone still shows:** the Activity person filter and reports. Capture is unchanged; `PersonLearner` stops suggesting a person once marked inactive and falls back to the default.

## Delivery — one push per phase, wait for CI between pushes

| # | Version | Delivers | Pure-JVM tests |
|---|---|---|---|
| 0 | — | This plan saved as `MMbank-UNASSIGNED-PLAN.md`; items added to `MMbank-IMPROVEMENT-PLAN.md` | — |
| 1 | 0.105.0 | `BalanceNeutralFiling`, apply point on all five paths, log row, wording | `BalanceNeutralFilingTest` (before/after cutoff, in and out, no adjustment → `createdAt`, mixed batch, currency), `AccountTimelineTest` wording, `MoneyPipelineScenarioTest`: adjust → file old rows → balance unchanged |
| 2 | 0.106.0, v37 | `historyOnly` column and behaviour, marking actions | `MigrationSqlTest` v36→v37, mapper round-trip, backup round-trip, additions to `AccountBalanceCalculatorTest`, `IntegrityCheckerTest`, `UnknownAccountSuggestionsTest`, `TransactionFilterEngineTest` |
| 3 | 0.107.0 | Person fill in Settings | `PersonBackfillTest` |
| 4 | 0.108.0 | Active / inactive people, assignment menus show active only | `PersonListsTest` (active only, current inactive kept, default always present, dedupe), `PersonLearnerTest` addition, backup round-trip |

Each phase: version bump in `app/build.gradle.kts`, tracker row marked ✅ with the version, feature-log entry, all in the same commit.

## Verification

- Push to `claude/mmbank-local-ai-parsing-4cfi14`; read the `test-build.yml` run. Green means the unit tests passed and the APK built.
- **Not covered by CI** (to list in the feature log and check on a device): the filing message, the "Before I started tracking" dialog and its date picker, the bulk action, the Settings person fill, the Active switch and the shortened person menus, and the history wording on screen.
- Device check: update an account's balance → file an older unassigned transaction into it → balance unchanged, history shows the "balance kept" line → file a transaction dated after the update → balance moves by its amount → mark the leftovers as history only → Unassigned card and the Integrity warning disappear, the rows are still in Activity.

## Limits to know about

- Balances already thrown off by earlier filing are not repaired retroactively; one more **Update balance** fixes each such account, and from then on it holds.
- Deleting or re-pricing an old row still moves the balance (it is logged in the account history, as today).
- The running balance shown for dates *before* the cutoff shifts when old rows are filed; from the cutoff onward it is unchanged.
