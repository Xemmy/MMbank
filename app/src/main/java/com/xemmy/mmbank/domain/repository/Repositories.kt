package com.xemmy.mmbank.domain.repository

import com.xemmy.mmbank.domain.model.IgnoredKeyword
import com.xemmy.mmbank.domain.model.MerchantAccountMapping
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.SenderLabel
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import com.xemmy.mmbank.domain.model.WalletBalance
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    fun observeTransactions(): Flow<List<Transaction>>
    fun observeRecent(limit: Int): Flow<List<Transaction>>
    /**
     * Every entry balances are derived from, in the active partition. Exact
     * (BigDecimal) and includes rows belonging to no account, so the Accounts
     * and Home screens can surface money that is in no balance. Fold it with
     * [com.xemmy.mmbank.domain.account.AccountBalanceCalculator].
     */
    fun observeLedgerEntries(): Flow<List<AccountBalanceCalculator.LedgerEntry>>
    /** Dedup + insert; the heart of the automated capture pipeline. */
    /**
     * Records a parsed capture. [dedupWindow] runs the ±2-minute
     * same-amount/currency/direction check that catches one purchase arriving
     * on two channels seconds apart; the paste importer turns it off for
     * messages whose clock time it doesn't actually know, where a same-day
     * coincidence would otherwise silently swallow a real transaction (it
     * guards re-imports with [findIdByRawMessage] instead).
     */
    suspend fun recordParsed(
        parsed: ParsedTransaction,
        message: CapturedMessage,
        dedupWindow: Boolean = true,
    ): IngestResult

    /** Id of the transaction already captured from this exact message text, if any. */
    suspend fun findIdByRawMessage(rawMessage: String): Long?

    /**
     * One merchant's real purchase history, oldest first (trimmed,
     * case-insensitive match) -- the baseline
     * [com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector] judges a
     * charge against. Defaults to empty so test fakes needn't implement it.
     */
    suspend fun spendHistoryForMerchant(merchant: String): List<Transaction> = emptyList()
    /** Inserts a user-entered transaction (no dedup — a manual entry is intentional). */
    suspend fun recordManual(transaction: Transaction): Long
    /**
     * Inserts a "Balance adjustment" row and logs it with the user's [reason]
     * (a [com.xemmy.mmbank.domain.account.BalanceChangeReason] name) and [note].
     * Defaults to a plain [recordManual] so test fakes needn't implement it.
     */
    suspend fun recordAdjustment(transaction: Transaction, reason: String?, note: String?): Long =
        recordManual(transaction)
    /** Updates an existing transaction's editable fields (manual edit flow). */
    suspend fun update(transaction: Transaction)
    suspend fun assignAccount(transactionId: Long, accountId: Long?)
    /**
     * Assigns [accountId] to the already-captured transactions matched by
     * [ruleName] that are still unassigned. Returns how many rows changed.
     */
    suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int
    /** Assigns/clears the person a transaction belongs to. */
    suspend fun setPerson(transactionId: Long, person: String?)
    /** Sets the spending category (groceries, dining, …) on a transaction. */
    suspend fun setSpendingCategory(transactionId: Long, category: SpendingCategory)
    /** Renames a person on every transaction carrying it; null [to] clears them. */
    suspend fun renamePerson(from: String, to: String?)
    suspend fun delete(transactionId: Long)
    /** Multi-select edit: sets spending category on every id in [ids] at once. Returns affected count. */
    suspend fun bulkSetCategory(ids: List<Long>, category: SpendingCategory): Int
    /** Multi-select edit: sets the analytics-exclusion flag on every id in [ids] at once. Returns affected count. */
    suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int
    /** Multi-select delete. Returns the number of rows removed. */
    suspend fun deleteMany(ids: List<Long>): Int
    /** Multi-select edit: assigns (or clears, when null) the account on every id in [ids] at once. Returns affected count. */
    suspend fun bulkAssignAccount(ids: List<Long>, accountId: Long?): Int

    /**
     * Like [bulkAssignAccount], but every row counts toward [accountId]'s balance
     * even when it is dated before the account existed. Only for a brand-new
     * account whose opening balance was worked out to expect exactly these rows
     * (`OpeningBalanceSolver`); everywhere else, old rows must not move a balance
     * that was already corrected (see `BalanceNeutralFiling`).
     */
    suspend fun bulkAssignAccountCounted(ids: List<Long>, accountId: Long): Int = bulkAssignAccount(ids, accountId)
    /** Multi-select edit: assigns (or clears, when null) the person on every id in [ids] at once. Returns affected count. */
    suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int
    /**
     * A one-shot snapshot of every real (non-demo) transaction, for the
     * background retention job to plan against -- unlike [observeTransactions],
     * this never switches to the demo partition, so demo rows can never be
     * evaluated (or deleted) by a job that runs independent of Demo mode.
     */
    suspend fun getAllReal(): List<Transaction>
    /** Real, non-demo rows still missing a dynamic category (plain Uncategorized or a pending AI suggestion). */
    fun observeUncategorized(): Flow<List<Transaction>>
    /** Assigns (or clears, with null) a dynamic [Category] on a transaction; clears any pending suggestion. */
    suspend fun setCategory(transactionId: Long, categoryId: Long?)
    /** Multi-select edit: assigns (or clears, when null) a dynamic category on every id in [ids] at once. Returns affected count. */
    suspend fun bulkSetCategoryId(ids: List<Long>, categoryId: Long?): Int
    /** Dismisses a pending AI category suggestion without assigning a category. */
    suspend fun dismissCategorySuggestion(transactionId: Long)
    /** Moves every transaction on [oldCategoryId] onto [newCategoryId] (or clears it, when null). Returns affected count. */
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?): Int
}

interface AccountRepository {
    /** Active accounts for the current view — demo accounts while Demo mode is on. */
    fun observeActive(): Flow<List<Account>>
    /**
     * Active REAL accounts only, regardless of Demo mode. Config surfaces (rule
     * "assign to account", pickers) use this so a rule can't be pinned to a demo
     * account that vanishes when Demo mode is switched off.
     */
    fun observeActiveReal(): Flow<List<Account>>
    /** Archived accounts in the current view; they stay out of every list and picker. */
    fun observeArchived(): Flow<List<Account>>
    suspend fun save(account: Account): Long
    suspend fun archive(account: Account)
    suspend fun unarchive(account: Account)
    suspend fun delete(account: Account)
    /** The change log of one account, newest first; empty by default so test fakes needn't implement it. */
    fun observeChanges(accountId: Long): Flow<List<com.xemmy.mmbank.domain.model.AccountChange>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

interface ParsingRuleRepository {
    /** All rules for display — demo rules while Demo mode is on. */
    fun observeAll(): Flow<List<ParsingRule>>
    /** Real rules only, regardless of Demo mode — used by the capture parser. */
    fun observeAllReal(): Flow<List<ParsingRule>>
    suspend fun getEnabled(): List<ParsingRule>
    suspend fun getById(id: Long): ParsingRule?
    suspend fun save(rule: ParsingRule): Long
    suspend fun delete(id: Long)
}

interface SenderLabelRepository {
    /** sender -> user label. */
    fun observeLabels(): Flow<Map<String, String>>
    suspend fun setLabel(sender: String, label: String)
    suspend fun clearLabel(sender: String)
}

/** The keywords capture must skip — see [IgnoredKeyword]. */
interface IgnoredKeywordRepository {
    fun observeAll(): Flow<List<IgnoredKeyword>>
    /**
     * Stores [keyword] (trimmed, lowercased). Returns false, storing nothing,
     * when it is shorter than [MIN_LENGTH] characters: a one- or two-letter
     * keyword would mute most real bank messages. Re-adding is a no-op.
     */
    suspend fun add(keyword: String, now: Long): Boolean
    suspend fun delete(id: Long)

    companion object {
        const val MIN_LENGTH = 3
    }
}

/** The message templates capture must skip — see [IgnoredMessagePattern]. */
interface IgnoredMessageRepository {
    fun observeAll(): Flow<List<IgnoredMessagePattern>>
    /**
     * Stores [sample] as a template to ignore. Re-adding a sample whose
     * fingerprint is already stored replaces that row rather than adding a
     * second one. Returns false (storing nothing) for a sample with no
     * fingerprint at all — punctuation, or digits alone — since that would
     * match nothing and only clutter the list.
     */
    suspend fun add(sample: String, now: Long): Boolean
    suspend fun delete(id: Long)
}

interface PortfolioRepository {
    fun observeBalances(): Flow<List<AssetBalance>>
    /** Per-wallet totals (Spot, Funding, Earn, Trading Bots, …). */
    fun observeWallets(): Flow<List<WalletBalance>>
    /**
     * Pulls spot balances + USD prices from Binance, plus a best-effort
     * per-wallet overview (Earn/Funding/Bots…). Returns the number of spot
     * assets stored.
     */
    suspend fun syncSpotBalances(): Result<Int>
}

interface RatesRepository {
    fun observeRates(): Flow<List<ExchangeRate>>
    /** Daily rate recordings (quote per 1 [baseCurrency]) for the currencies the user holds, oldest first. */
    fun observeHistory(baseCurrency: String): Flow<List<com.xemmy.mmbank.domain.model.ExchangeRateOnDay>>
    /** Refreshes Forex rates for [baseCurrency]. Returns the number of rates stored. */
    suspend fun refresh(baseCurrency: String): Result<Int>
}

/**
 * Dynamic categories + learned merchant mappings (Phase 1/2 of the smart
 * categorization engine). Independent of the fixed [SpendingCategory] enum,
 * which stays in place for the Dashboard/Insights breakdowns.
 */
interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>
    fun observeMappings(): Flow<List<MerchantCategoryMapping>>
    suspend fun getById(id: Long): Category?
    /** Inserts a new category (id = 0) or updates an existing one; returns its id. */
    suspend fun save(category: Category): Long
    suspend fun delete(id: Long)
    /**
     * Re-homes the learned merchant rules that pointed at a category being
     * deleted: onto [newCategoryId], or dropped entirely when it is null.
     * Returns the number of rules affected.
     */
    suspend fun repointMappings(oldCategoryId: Long, newCategoryId: Long?): Int
    /** Resolves [merchant] against confirmed mappings only (see [com.xemmy.mmbank.domain.parser.CategoryMatcher]). */
    suspend fun findCategoryForMerchant(merchant: String): Long?
    /** An unconfirmed AI guess staged for [merchant], if any. */
    suspend fun findPendingSuggestion(merchant: String): MerchantCategoryMapping?
    /**
     * Case-insensitive exact-name lookup -- bridges [com.xemmy.mmbank.domain.model.CategoryMigrationMapping]'s
     * enum-to-name targets onto a real category id, at capture time
     * (see [com.xemmy.mmbank.domain.parser.CaptureCategoryResolver]) and in
     * the v21->v22 migration's backfill.
     */
    suspend fun findCategoryIdByName(name: String): Long?
    /**
     * Confirms merchant -> category and backfills every still-uncategorized
     * real transaction from that merchant onto it. Returns rows backfilled.
     */
    suspend fun learnMapping(merchant: String, categoryId: Long, isExactMatch: Boolean = true): Int
    /**
     * Stages an unconfirmed AI category guess for [merchant] and flags every
     * still-uncategorized real transaction from that merchant for review.
     * Returns rows flagged.
     */
    suspend fun suggestPending(merchant: String, name: String, icon: String?, colorHex: String?): Int
}

/**
 * Detected recurring charges (see [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]),
 * persisted so the Subscriptions Radar screen has something to show without
 * re-scanning the whole ledger on every launch.
 */
interface SubscriptionRepository {
    /** Active (non-dismissed) detections, soonest renewal first. */
    fun observeActive(): Flow<List<Subscription>>
    /** Replaces the stored detection set with a fresh scan's results (one row per merchant). */
    suspend fun replaceAll(detected: List<Subscription>)
    suspend fun dismiss(id: Long)
}

/**
 * Manually tracked bills -- see [Bill]. Unlike [SubscriptionRepository],
 * every row here exists because the user added it, so there's full CRUD
 * instead of a detection-scan replace.
 */
interface BillRepository {
    /** All bills, soonest due first. */
    fun observeAll(): Flow<List<Bill>>
    /** Inserts a new bill (id = 0) or updates an existing one; returns its id. */
    suspend fun save(bill: Bill): Long
    /**
     * Marks a bill paid: a recurring bill's due date advances one cycle
     * (see [com.xemmy.mmbank.domain.intelligence.BillAdvancer]); a one-off
     * bill is deleted instead of persisting a paid one-off forever.
     */
    suspend fun markPaid(billId: Long)
    suspend fun delete(billId: Long)
}

/**
 * Named savings/debt-payoff targets (see [com.xemmy.mmbank.ui.screens.goals.GoalsScreen]).
 * Independent of [com.xemmy.mmbank.ui.screens.dashboard.GoalState], the
 * Dashboard's single net-worth target — a ledger can hold several of these
 * at once (Emergency Fund, Vacation, Debt Payoff, …).
 */
interface SavingsGoalRepository {
    /** Non-archived goals, newest first. */
    fun observeActive(): Flow<List<SavingsGoal>>
    /** Inserts a new goal (id = 0) or updates an existing one; returns its id. */
    suspend fun save(goal: SavingsGoal): Long
    /** Adds [amount] to the goal's current progress ("Auto-Allocate Surplus"). */
    suspend fun allocate(goalId: Long, amount: BigDecimal)
    suspend fun archive(goalId: Long)
    suspend fun delete(goalId: Long)
}

/** People shared expenses are split with -- see [Friend]. */
interface FriendRepository {
    /** Non-archived friends, alphabetical. */
    fun observeActive(): Flow<List<Friend>>
    /** Inserts a new friend (id = 0) or updates an existing one; returns its id. */
    suspend fun save(friend: Friend): Long
    suspend fun archive(friendId: Long)
}

/**
 * Shared-expense groups, their expenses and per-participant splits -- see
 * [SplitGroup] / [GroupExpense] / [com.xemmy.mmbank.domain.split.GroupBalances].
 */
interface SplitGroupRepository {
    /** Non-archived groups, newest first, each with its member id list already resolved. */
    fun observeActive(): Flow<List<SplitGroup>>
    /** Inserts a new group (id = 0) or updates an existing one, replacing its member list wholesale; returns its id. */
    suspend fun save(group: SplitGroup): Long
    suspend fun archive(groupId: Long)
    /** Every group's expenses at once -- balances are computed across the whole ledger, not fetched per group. */
    fun observeAllExpenses(): Flow<List<GroupExpense>>
    fun observeAllSplits(): Flow<List<ExpenseSplit>>
    /**
     * Inserts a new expense (`expense.id == 0`) or updates an existing one,
     * with its already-computed splits (see [com.xemmy.mmbank.domain.split.SplitAllocator]),
     * replacing the old split rows wholesale on an update; returns the expense id.
     */
    suspend fun saveExpense(expense: GroupExpense, splits: List<ExpenseSplit>): Long
    suspend fun deleteExpense(expenseId: Long)
}

/**
 * Learned merchant -> account rules.
 *
 * Its own interface rather than members on [TransactionRepository] or
 * [CategoryRepository]: the former already has around twenty-five members and
 * three hand-written test fakes that each pay for every new one, and the
 * latter is about categories — a category correction must never disturb
 * account knowledge, or vice versa.
 */
interface MerchantAccountRepository {
    fun observeMappings(): Flow<List<MerchantAccountMapping>>

    /** Resolves [merchant] against learned rules: exact, then normalized key. */
    suspend fun findAccountForMerchant(merchant: String): Long?

    /** Every rule currently pointing at [accountId]. */
    suspend fun findMappingForAccount(accountId: Long): List<MerchantAccountMapping>

    /**
     * Remembers merchant -> account and backfills every real transaction from
     * that merchant still left unassigned. Returns how many rows were filled.
     */
    suspend fun learnMapping(merchant: String, accountId: Long): Int

    /** Drops the rule for [merchant]; already-assigned transactions stay put. */
    suspend fun forget(merchant: String): Int
}

/**
 * What a transaction *is*, as opposed to its fields: kinds, learned kinds and
 * manual transfers (see [com.xemmy.mmbank.domain.model.TransactionKind]).
 *
 * Its own interface, implemented by the same class as [TransactionRepository]
 * (they share the transfer-linking logic), so the three hand-written
 * [TransactionRepository] test fakes don't each pay for these members.
 */
interface LedgerRepository {

    /**
     * Saves both sides of a transfer or card payment the user entered by
     * hand, linked as one pair, in one database transaction. Returns how many
     * legs were written (1 when one side is outside the tracked accounts).
     */
    suspend fun recordTransfer(draft: com.xemmy.mmbank.domain.ledger.TransferLegs.Draft): Int

    /**
     * Re-classifies one transaction (marking it user-set, so automation never
     * overrides it again). Moving a linked leg out of transfer/card payment
     * releases its partner; moving an unlinked one in tries to find its
     * other leg. With [rememberForMerchant], the merchant -> kind rule is
     * stored and applied to that merchant's other automatically-classified
     * rows; returns how many of those were changed.
     */
    suspend fun setKind(
        transactionId: Long,
        kind: com.xemmy.mmbank.domain.model.TransactionKind,
        rememberForMerchant: Boolean,
    ): Int

    /** The kind learned for [merchant] (exact, then normalized key), or null. */
    suspend fun findKindForMerchant(merchant: String): com.xemmy.mmbank.domain.model.TransactionKind?

    /** Drops the learned kind for [merchant]; past rows keep their kind. */
    suspend fun forgetMerchantKind(merchant: String): Int

    /** Every learned merchant -> kind rule, newest first. */
    fun observeMerchantKinds(): Flow<List<com.xemmy.mmbank.domain.model.MerchantKindMapping>>
}
