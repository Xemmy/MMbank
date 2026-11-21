# Pay Later plans (Tabby-style) + manual subscription linking

## Context
The user wants a screen like Tabby's "Pay Later purchases": an amount due next ("Due in 9 days, AED 81.83"), the total still owed, and upcoming installments grouped by date ("Carrefour · Payment 2 of 4") with a Pay action.

MMbank has nothing like this today:
- Tabby SMS rules only cover card purchases, adjustments and cashback.
- Bills, Subscriptions and the Debt planner don't model "N payments of X".

What the user chose:
- **Entry:** add plans by hand, plus a shortcut from a captured transaction.
- **Pay:** marking an installment paid records a real transaction.
- **Where it shows:** a Plan hub card, and inside Upcoming payments.
- **Also:** a way to manually attach transactions to a subscription.

Ship this as two commits, each with its own version bump and green CI:
- Part A (Pay Later) → 0.125.0 / versionCode 181
- Part B (subscription linking) → 0.126.0 / versionCode 182

---

## Part A: Pay Later plans

### Data layer (DB 40 → 41)

**New entities** in `data/local/entity/Entities.kt`, with matching domain models in `domain/model/Models.kt` and `toEntity()`/`toDomain()` mappers:

- `PayLaterPlanEntity` (table `pay_later_plans`):
  - id, merchant, provider (default "Tabby")
  - totalAmount: BigDecimal, currencyCode
  - installmentCount, cadence: `PayLaterCadence` (WEEKLY, BIWEEKLY, MONTHLY)
  - payFromAccountId: Long?, payToAccountId: Long? (the Tabby liability account, if any)
  - sourceTransactionId: Long? (the original purchase, if it was captured)
  - note, createdAt, isDemo=false
- `PayLaterInstallmentEntity` (table `pay_later_installments`):
  - id, planId (FK → plans, ON DELETE CASCADE, indexed), number (1..n), amount
  - dueDateMillis, paidAtMillis: Long?, paymentTransactionId: Long?

**Migration:**
- `MIGRATION_40_41_SQL` in `data/local/MigrationSql.kt`: CREATE both tables and the index.
- Add the `Migration(40,41)` object in `AppDatabase.kt`, bump `version = 41`, and append it to `.addMigrations(...)`.

**DAO and repository:**
- `PayLaterDao` in `data/local/dao/Daos.kt`:
  - Flows of plans and installments; insert a plan together with its installments (`@Transaction`); update an installment; delete a plan.
- `PayLaterRepository` (interface in `domain/repository/Repositories.kt`, implemented in `data/repository/SimpleRepositories.kt`).
- Wire it in `di/AppContainer.kt`.

**Backup:**
- `BackupPayLaterPlan` / `BackupPayLaterInstallment` DTOs in `data/backup/BackupManager.kt`, following the BackupBill pattern.
- Old backups without these sections must restore to empty.

### Domain (pure, unit-tested)

**`domain/paylater/PayLaterSchedule.kt`**
- `generate(total, count, firstDue, cadence)`:
  - Split the total evenly to the cent; the rounding remainder goes on the last installment.
  - Due dates step by cadence. Monthly uses `plusMonths`, which clamps the 31st correctly.
- An option to mark installment 1 as already paid, since Tabby takes the first payment at checkout.

**`domain/paylater/PayLaterSummary.kt`**
- Next due date, its amount, and days until it.
- Total remaining, kept per currency and never summed across currencies.
- Unpaid installments grouped by due date (this drives the screen's date cards).
- Per-plan progress ("Payment k of n"); a plan counts as finished once everything is paid.

**`domain/paylater/PayLaterPayment.kt`** builds the transaction(s) for a payment:
- **Purchase was already captured** (`sourceTransactionId` set) **and `payToAccountId` set:**
  - Record a transfer pair, payFrom → payTo, linked via `linkedTransactionId`. This is the same pattern existing transfers use.
  - Spending isn't double-counted, and the Tabby liability balance goes down.
- **Purchase was already captured, but no payTo account:**
  - Record a debit from payFrom with `excludeFromStats = true`, so the purchase isn't counted twice.
- **Purchase was never captured:**
  - Record a normal SPEND from payFrom, with merchant = plan merchant and the plan's category.
- **Undo payment:** delete the payment transaction(s) and clear `paidAtMillis` / `paymentTransactionId`.

### UI

**`ui/screens/paylater/PayLaterScreen.kt`** (+ `PayLaterViewModel`), modelled on the screenshot:
- **Header:**
  - "Due in N days" (or "Overdue by N days")
  - The large next-due amount
  - "Total due AED X"
- **Buttons:**
  - "Add plan"
  - "History" toggle (paid installments and finished plans)
- **"Upcoming payments" list:** one card per due date. Each row shows:
  - Merchant
  - "Payment k of n"
  - Amount
  - A **Pay** button
- **Pay** opens a sheet:
  - Choose the account to pay from (defaults to the plan's payFrom)
  - Date (defaults to today)
  - Confirm
- Tabby's **Extend** button is replaced by **Reschedule**, which edits the due date.
- Tapping a row opens plan detail.

**Plan detail screen** (`pay_later/{planId}`):
- Every installment with its paid/unpaid state; undo on paid ones.
- Edit the plan, or delete it (with a confirmation).

**Add/edit plan form:**
- Fields: merchant, provider, total, number of payments (default 4), cadence (default monthly), first due date, "first payment already paid", pay-from account, Tabby account (optional).
- **Shortcut from a transaction:** add a "Split with Pay Later" action in `TransactionEditorScreen.kt`, next to the existing "Repeat" action. It opens the form prefilled with merchant, amount, date and `sourceTransactionId`, and picks the transaction's account as `payToAccountId` when that account is a CREDIT_CARD or LOAN.

**Navigation:**
- Add `Routes.PAY_LATER`, `PAY_LATER_DETAIL` and `PAY_LATER_EDITOR` in `ui/navigation/AppNavigation.kt`.
- Add the `composable` entries in `MainActivity.AppNavHost`.
- Map them to `AppTab.PLAN` in `TabRouting.ownerByBaseRoute`.

### Integrations

- **Plan hub:**
  - Add `PlanEntry.PAY_LATER` ("Pay Later", "Tabby-style split purchases and what's due next") in `ui/screens/plan/PlanHub.kt`, after SUBSCRIPTIONS.
  - Add `PlanStatus.payLater(...)` returning "AED 81.83 due in 9 days" (or "N overdue"), wired in `PlanViewModel.kt`.
- **Upcoming payments:**
  - Add `Kind.PAY_LATER("Pay Later")` in `domain/intelligence/UpcomingPayments.kt`.
  - Add an `installments` parameter to `build()`. Each unpaid installment becomes an Item named "Carrefour · 2 of 4". The existing horizon and grouping logic then applies to it.
  - Feed the parameter from `UpcomingPaymentsViewModel`.

### Tests (same commit)

- `PayLaterScheduleTest`: even split, remainder on the last installment, monthly clamping at the 31st, weekly/biweekly dates, first-paid option.
- `PayLaterSummaryTest`: next due, overdue, per-currency totals, grouping by date, a finished plan drops out.
- `PayLaterPaymentTest`: the three transaction shapes above, and undo.
- `UpcomingPaymentsTest`: installments included, paid ones excluded.
- `PlanStatusTest`: payLater lines.
- `EntityMappersTest` and `BackupMappersTest`: round-trips for both new entities.
- `MigrationSqlTest`: 40→41 creates the tables, and cascade delete works.
- `AppNavigationTest`: the new routes map to the PLAN tab.

### Housekeeping

- `app/build.gradle.kts`: versionCode 181, versionName 0.125.0.
- Add the item to `MMbank-IMPROVEMENT-PLAN.md` marked ✅ 0.125.0, with "Not covered by CI" notes for the Compose UI.

---

## Part B: Manually attach transactions to a subscription

**How detection works today:**
- `SubscriptionRadarEngine.detect` groups transactions by merchant key.
- `SubscriptionMerge` keeps ids stable per merchant.

So a charge with a differently named merchant (e.g. "APPLE.COM/BILL" vs "Apple") can't be attached.

**Data (DB 41 → 42):**
- New table `subscription_links(transactionId PK, subscriptionId, FK cascade)`.
- `SubscriptionLinkEntity`, a domain model and mappers.
- `MIGRATION_41_42_SQL`.
- DAO methods and a backup DTO.

**Domain: `domain/intelligence/SubscriptionLinks.kt`**
- `apply(transactions, links, subscriptions)` rewrites the merchant of each linked transaction to its subscription's merchant before detection runs. This happens both in `SubscriptionRepositoryImpl` (scan, `SimpleRepositories.kt:279`) and in the background scan (`AppContainer.kt:901`).
- Linked charges therefore count toward occurrences, average, last seen and next due.
- Rules for linked charges:
  - They are never treated as noise.
  - A subscription with at least one linked charge plus a matching chain survives detection.

**Manual subscription:**
- "Add subscription" on the Subscriptions screen: merchant, amount, cadence, next date.
- Requires an `isManual` column on `subscriptions` (in the same 41→42 migration). `SubscriptionMerge` never overwrites amount/cadence on manual rows, and never drops them.

**UI:**
- **Transaction editor:** a new "Add to subscription" action. A picker lists the active subscriptions plus "New subscription from this charge".
- **Subscriptions screen:** tapping a subscription opens a sheet with:
  - Its linked and auto-matched charges
  - "Add charge", which picks from recent debits that aren't already linked
  - Unlink per row

**Tests:**
- `SubscriptionLinksTest`: merchant rewrite; a linked charge changes average/next due; a manual row survives a merge.
- Mapper, backup and migration round-trips.

**Housekeeping:** version 182 / 0.126.0, and mark the item ✅ in the improvement plan.

---

## Verification

- Push each part separately to `claude/mmbank-local-ai-parsing-4cfi14` and wait for each CI run to finish before the next push (`cancel-in-progress`).
- A green run means:
  - All new pure-JVM tests pass
  - The migrations run on real SQLite
  - The APK builds
- Ask the user to sideload the debug APK artifact and check by hand:
  - Add a 4-payment Carrefour plan; check the header and date cards against the screenshot.
  - Pay one installment from a bank account; check the balances.
  - Undo that payment.
  - Check that the plan appears in Upcoming payments and on the Plan hub card.
  - Link a renamed charge to a subscription.
- **Not covered by CI:** Compose rendering, navigation, and the transaction-editor actions.
