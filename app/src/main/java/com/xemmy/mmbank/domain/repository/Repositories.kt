package com.xemmy.mmbank.domain.repository

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SenderLabel
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
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
    fun observeAll(): Flow<List<ParsingRule>>
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
