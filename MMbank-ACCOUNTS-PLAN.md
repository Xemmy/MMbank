# MMbank — Accounts: quick add, update-if-exists, change log

## Context

Today, adding an account means filling a ~20-field dialog (`AccountFormScreen.kt`), and nothing stops a second copy of the same account or card being created. Balances are derived (`openingBalance + credits − debits`, `AccountBalanceCalculator`), so several things move a balance **without leaving any trace**:

- editing the opening balance in the account form;
- editing a transaction's amount, direction or date, moving it to another account, or deleting it;
- retention pruning, which rewrites `openingBalance` (`RetentionPruner.kt:71`);
- restoring a backup.

`IntegrityChecker` can already say *that* the books differ from the bank's printed balance, but not *when* the difference appeared or *what* caused it.

Goal: one short "add or update" flow for any account type, no duplicates, and a per-account history that explains every balance change.

---

## Answer: should there be an "External" account for outside transfers?

**No. Do not add one.** Reasons specific to this codebase:

- The ledger is single-entry. Every account balance is real money you hold. An "External" account would be a contra account whose balance is just the negative of everything that ever left, so it would have to be excluded from net worth, the carousel, `IntegrityChecker`, the debt planner, reports and backups. Each exclusion is a place to get wrong.
- `TransferLegs` already supports a missing side ("Outside / not tracked" in `TransactionEditorDialogs.kt:720`). No fake account is needed to record a one-sided move.

**What to do instead** — "outside" really means two different things, and they need different handling:

| The other side is… | Record it as | Why |
|---|---|---|
| **Your own account that isn't in the app yet** | Add it as a real account (quick add makes this cheap), then it's a normal linked transfer | It's your money; net worth should include it |
| **Someone else** (family, friend, landlord) | A normal Spent / Received row with the **person** set, category Transfers | Money really left or entered your net worth |

**Gap found:** a one-sided transfer is saved today with kind `TRANSFER`, and `LedgerMath` counts `TRANSFER` as neither spend nor income. So money sent to another person through the transfer editor disappears from Spent. Phase 4 fixes this by asking "Whose account is it?" when one side is Outside.

---

## Design

### 1. Quick add with update-if-exists

**New pure object `domain/account/AccountUpsertResolver.kt`.** Input: a draft (type, name, bank, currency, account number, IBAN, card endings) plus all accounts, active and archived. Output:

- `Existing(account, matchedOn, strength)` — `STRONG` on IBAN (normalized, exact) or account number / card ending (reuse `AccountNumberMatcher`, same 3-digit minimum and tie → no match rule); `WEAK` on same name + bank + currency + type.
- `Ambiguous(accounts)` — several candidates; the user picks.
- `New`.

**New pure object `domain/account/AccountMerge.kt`.** Fills blank fields on the existing account from the draft and appends new card endings (`AccountFormValidator.normalizeCardEndings`). It never overwrites a non-blank field; conflicts are returned as a list for the user to confirm.

**Quick add sheet** (new `ui/screens/accounts/QuickAddAccountSheet.kt`, opened by the Accounts FAB):

- Fields: type chips (all 7 `AccountType`s), name, **current balance**, last digits (optional), "Paste bank details" (existing `BankDetailsParser`). "More details" opens the existing full `AccountDialog`.
- On save the resolver runs:
  - `New` → create; opening balance = the typed current balance.
  - `STRONG` → no new row. Show "You already have *ADCB Current ••1234*": balance now X, you entered Y → **Update balance** (posts the adjustment, section 2) and merge blank fields. An archived match offers **Restore** first.
  - `WEAK` / `Ambiguous` → "Same as …?" with **Update it** / **Create separate**.
- The full form's Save runs the same resolver, so duplicates are blocked there too.

**Suggestions from captured messages.** New pure `domain/account/UnknownAccountSuggestions.kt`: groups unassigned transactions by `accountNumberSuffix` that matches no account, with message count, sender label, currency and the latest `reportedBalance`/kind (`AVAILABLE_CREDIT` → suggest credit card). Shown as a card on Accounts ("5 messages from card ••4543 — add it?"). Two actions:

- **Add as new account** — prefilled quick add. New pure `OpeningBalanceSolver` sets the opening balance so the computed balance equals the bank's printed one at that message's time.
- **It's my existing account…** — appends the ending to that account's `cardEndings`.

Both backfill the matching unassigned rows with the existing `bulkAssignAccount`.

### 2. Update balance, with a reason

Keep the existing mechanism: `buildBalanceAdjustment` (`AccountsViewModel.kt:259`) posts one visible, deletable `ADJUSTMENT` row. Add a required reason — `BalanceChangeReason`: missed transaction, cash count, interest or fees, market value, statement correction, initial setup, other — plus an optional note. Both go to the change log. This same dialog is the "update balance" path for cash, investments and loans, which have no bank messages.

### 3. Change log (schema v36)

New table `account_changes`, with `MIGRATION_35_36` in raw SQL (`MigrationSql.kt`, covered in `MigrationSqlTest`), entity + domain model + mappers + round-trip test per CLAUDE.md:

| Column | Notes |
|---|---|
| `id`, `accountId`, `accountName` | No foreign key and a name snapshot, so the log survives account deletion |
| `timestamp`, `type`, `source` | `source`: USER, CAPTURE_SUGGESTION, RETENTION, BACKUP_RESTORE |
| `field`, `oldValue`, `newValue` | For detail edits; one row per changed field |
| `balanceDelta`, `currencyCode` | Exact `BigDecimal` effect on the balance, in `currencyCode`, computed from the change itself (no balance query needed) |
| `transactionId`, `reason`, `note`, `isDemo` | |

`type`: CREATED, DETAILS_EDITED, OPENING_BALANCE_CHANGED, BALANCE_ADJUSTED, ARCHIVED, RESTORED, DELETED, TRANSACTION_EDITED, TRANSACTION_MOVED_IN, TRANSACTION_MOVED_OUT, TRANSACTION_DELETED, RETENTION_FOLDED, BACKUP_RESTORED.

**Not logged:** ordinary new transactions (captured or manual). They are already the ledger; the history screen merges them in.

**Pure diff builders** (unit tested): `AccountChangeDiff.diff(old, new)` and `LedgerChangeDiff.diff(oldTx, newTx)` → log rows.

**Write points**, each inside the same Room transaction as the change:

- `AccountRepositoryImpl` (`SimpleRepositories.kt:60`): `save` (read old row, diff), `archive`, `unarchive`, `delete`.
- `TransactionRepositoryImpl`: `update`, `assignAccount`, `bulkAssignAccount`, `delete`, `deleteMany`/`deleteAndRelease`, and the adjustment path.
- `RetentionPruner.kt:71`: one RETENTION_FOLDED row per `PrunedBalanceFolder.Adjustment`.
- `BackupManager`: export/import the table (format version +1) and add one BACKUP_RESTORED row per account after import.

Log rows older than the transaction-retention cutoff are pruned with it; with retention off they are kept.

### 4. History screen — "understand why"

New route `account_history/{id}` (`AppNavigation.kt`), opened from the account card. New pure `domain/account/AccountTimeline.kt` merges transactions and log rows into one list, newest first, with a running balance and a plain-language cause per row ("Opening balance changed 1,000 → 1,250 by you", "Purchase moved to Tabby Card", "Folded by retention clean-up"). Filter chips: All / Balance changes / Edits. Amounts honour hide-balances (`LocalHideBalances`, `asMoneyOrMask`).

**Drift explainer.** New pure `domain/ledger/DriftWindow.kt`: walks an account's bank-printed balances backward to find the last one that matched and the first that didn't. The existing BALANCE_DRIFT issue on the Integrity screen gains "See what changed": the history filtered to that window ("Difference of −120.00 appeared between 3 Nov 14:02 and 5 Nov 09:10 — 4 transactions, 1 edit").

---

## Delivery — one push per phase, wait for CI between pushes

| # | Version | Delivers | Pure-JVM tests | Status |
|---|---|---|---|---|
| 1 | 0.100.0, schema v36 | `account_changes` table, migration, mappers, diff builders, all write points, backup | `MigrationSqlTest` v35→v36, mapper round-trip, `AccountChangeDiffTest`, `LedgerChangeDiffTest`, backup round-trip | ✅ 0.100.0, CI run 43387209299 green |
| 2 | 0.101.0 | History screen, `AccountTimeline`, reason on balance adjustment | `AccountTimelineTest` (running balance, ordering, wording) | ✅ 0.101.0, CI run 43389467494 green |
| 3 | 0.102.0 | `AccountUpsertResolver`, `AccountMerge`, quick add sheet, duplicate guard in the full form | `AccountUpsertResolverTest` (IBAN, suffix, tie, archived, weak), `AccountMergeTest` | ✅ 0.102.0, CI run 43394048775 green |
| 4 | 0.103.0 | `UnknownAccountSuggestions`, `OpeningBalanceSolver`, backfill; "Whose account is it?" on one-sided transfers | Suggestion grouping, solver, `TransferLegsTest` additions | ✅ 0.103.0 |
| 5 | 0.104.0 | `DriftWindow` + "See what changed" on Integrity | `DriftWindowTest` | ⏳ |

Each phase bumps `versionCode` by 1 (from 152), marks its tracker row ✅, and adds a feature-log entry.

## Verification

- Push to `claude/mmbank-local-ai-parsing-4cfi14`; read the `test-build.yml` run. Green means unit tests passed and the APK built.
- **Not covered by CI** (list in the feature log; check on a device with the built APK): the quick add sheet and history screen rendering, navigation to `account_history`, the FAB entry point, and hide-balances masking on the new screens.
- Manual check on device: add an account → add it again with the same last digits → confirm it offers Update, not a second account → update the balance with a reason → open History and see the adjustment, its reason and the running balance → edit and delete a transaction and confirm both appear.
