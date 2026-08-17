package com.xemmy.mmbank.domain.repository

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
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
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    fun observeTransactions(): Flow<List<Transaction>>
    fun observeRecent(limit: Int): Flow<List<Transaction>>
    /** accountId -> net movement (credits - debits), for balance computation. */
    fun observeNetByAccount(): Flow<Map<Long, Double>>
    /** Dedup + insert; the heart of the automated capture pipeline. */
    suspend fun recordParsed(parsed: ParsedTransaction, message: CapturedMessage): IngestResult
    /** Inserts a user-entered transaction (no dedup — a manual entry is intentional). */
    suspend fun recordManual(transaction: Transaction): Long
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
    suspend fun save(account: Account): Long
    suspend fun archive(account: Account)
    suspend fun delete(account: Account)
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
