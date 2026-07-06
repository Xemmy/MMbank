package com.xemmy.mmbank.domain.repository

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SenderLabel
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
    suspend fun assignAccount(transactionId: Long, accountId: Long?)
    /** Assigns/clears the person a transaction belongs to. */
    suspend fun setPerson(transactionId: Long, person: String?)
    /** Renames a person on every transaction carrying it; null [to] clears them. */
    suspend fun renamePerson(from: String, to: String?)
    suspend fun delete(transactionId: Long)
}

interface AccountRepository {
    fun observeActive(): Flow<List<Account>>
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
