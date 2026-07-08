package com.xemmy.mmbank.data.backup

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// --- Backup payload DTOs: primitives only, amounts as plain-decimal strings ---

@Serializable
data class BackupAccount(
    val id: Long,
    val name: String,
    val type: String,
    val currencyCode: String,
    val openingBalance: String,
    val archived: Boolean,
    val notes: String?,
    val createdAt: Long,
    val accountNumber: String?,
    val bankName: String?,
    val accountTitle: String?,
    val iban: String?,
    val swift: String?,
    val outstandingBalance: String?,
    val nextInstallmentAmount: String?,
    val dueDayOfMonth: Int?,
    val profitRatePa: Double?,
    val maturityDateMillis: Long?,
    val loanNotes: String?,
)

@Serializable
data class BackupTransaction(
    val id: Long,
    val accountId: Long?,
    val amount: String,
    val currencyCode: String,
    val direction: String,
    val merchant: String?,
    val source: String,
    val sender: String?,
    val rawMessage: String?,
    val matchedRuleName: String?,
    val timestamp: Long,
    val createdAt: Long,
    val accountNumberSuffix: String?,
    val category: String = "NORMAL",
    val person: String? = null,
    val spendingCategory: String = "UNCATEGORIZED",
)

@Serializable
data class BackupRule(
    val id: Long,
    val name: String,
    val pattern: String,
    val senderPattern: String?,
    val direction: String,
    val defaultCurrency: String?,
    val priority: Int,
    val enabled: Boolean,
    val sampleText: String?,
    val createdAt: Long,
    val assignAccountId: Long? = null,
)

@Serializable
data class BackupSenderLabel(val sender: String, val label: String)

@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val exportedAtMillis: Long,
    val defaultCurrency: String,
    val cashbackSpendRaw: String,
    /** Saved people list (newline-separated); default keeps old backups importable. */
    val personsRaw: String = "",
    val accounts: List<BackupAccount>,
    val transactions: List<BackupTransaction>,
    val rules: List<BackupRule>,
    val senderLabels: List<BackupSenderLabel>,
)

/**
 * Exports the entire ledger (accounts incl. archived, transactions, custom
 * rules, sender labels, key settings) to a password-encrypted blob, and
 * restores it. Import REPLACES the current data — it runs inside one Room
 * transaction, so a failed import leaves the existing data untouched.
 *
 * Binance API keys are deliberately NOT exported: they live in the
 * Keystore-backed encrypted store and should be re-entered on a new device.
 */
class BackupManager(
    private val database: AppDatabase,
    private val preferences: AppPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(password: CharArray): ByteArray {
        val payload = BackupPayload(
            exportedAtMillis = System.currentTimeMillis(),
            defaultCurrency = preferences.defaultCurrency,
            cashbackSpendRaw = preferences.cashbackSpendRaw,
            personsRaw = preferences.personsRaw,
            accounts = database.accountDao().getAll().map { it.toBackup() },
            transactions = database.transactionDao().getAll().map { it.toBackup() },
            rules = database.parsingRuleDao().getAll().map { it.toBackup() },
            senderLabels = database.senderLabelDao().getAll().map { BackupSenderLabel(it.sender, it.label) },
        )
        return BackupCrypto.encrypt(json.encodeToString(payload).toByteArray(Charsets.UTF_8), password)
    }

    /**
     * Exports every transaction as a plain, UTF-8 CSV for spreadsheets. Unlike
     * [export] this is NOT encrypted — it carries no rule patterns or settings,
     * just the ledger rows, with account ids resolved to names. RFC-4180 quoting.
     */
    suspend fun exportCsv(): String {
        val accountNames = database.accountDao().getAll().associate { it.id to it.name }
        val txs = database.transactionDao().getAll().sortedByDescending { it.timestamp }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val header = listOf(
            "Date", "Direction", "Amount", "Currency", "Merchant", "Category",
            "Account", "Person", "Source", "Rule", "Sender",
        )
        val sb = StringBuilder()
        sb.append(header.joinToString(",", transform = ::csvEscape)).append("\r\n")
        for (t in txs) {
            val row = listOf(
                fmt.format(java.util.Date(t.timestamp)),
                t.direction.name,
                t.amount.toPlainString(),
                t.currencyCode,
                t.merchant.orEmpty(),
                t.spendingCategory.label,
                t.accountId?.let { accountNames[it] }.orEmpty(),
                t.person.orEmpty(),
                t.source.name,
                t.matchedRuleName.orEmpty(),
                t.sender.orEmpty(),
            )
            sb.append(row.joinToString(",", transform = ::csvEscape)).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    /** @return counts (accounts, transactions) for the status message. */
    suspend fun import(blob: ByteArray, password: CharArray): Pair<Int, Int> {
        val payload = json.decodeFromString<BackupPayload>(
            BackupCrypto.decrypt(blob, password).toString(Charsets.UTF_8)
        )
        database.withTransaction {
            database.transactionDao().clearAll()
            database.accountDao().clearAll()
            database.parsingRuleDao().clearAll()
            database.senderLabelDao().clearAll()

            database.accountDao().insertAll(payload.accounts.map { it.toEntity() })
            database.transactionDao().insertAll(payload.transactions.map { it.toEntity() })
            database.parsingRuleDao().insertAll(payload.rules.map { it.toEntity() })
            database.senderLabelDao().insertAll(
                payload.senderLabels.map { SenderLabelEntity(it.sender, it.label) }
            )
        }
        preferences.defaultCurrency = payload.defaultCurrency
        preferences.cashbackSpendRaw = payload.cashbackSpendRaw
        preferences.personsRaw = payload.personsRaw
        return payload.accounts.size to payload.transactions.size
    }

    // --- entity <-> backup mappers ---

    private fun AccountEntity.toBackup() = BackupAccount(
        id, name, type.name, currencyCode, openingBalance.toPlainString(), archived, notes, createdAt,
        accountNumber, bankName, accountTitle, iban, swift,
        outstandingBalance?.toPlainString(), nextInstallmentAmount?.toPlainString(),
        dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    )

    private fun BackupAccount.toEntity() = AccountEntity(
        id, name, AccountType.valueOf(type), currencyCode, BigDecimal(openingBalance), archived, notes, createdAt,
        accountNumber, bankName, accountTitle, iban, swift,
        outstandingBalance?.let(::BigDecimal), nextInstallmentAmount?.let(::BigDecimal),
        dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    )

    private fun TransactionEntity.toBackup() = BackupTransaction(
        id, accountId, amount.toPlainString(), currencyCode, direction.name, merchant,
        source.name, sender, rawMessage, matchedRuleName, timestamp, createdAt, accountNumberSuffix,
        category.name, person, spendingCategory.name,
    )

    private fun BackupTransaction.toEntity() = TransactionEntity(
        id, accountId, BigDecimal(amount), currencyCode, TransactionDirection.valueOf(direction), merchant,
        TransactionSource.valueOf(source), sender, rawMessage, matchedRuleName, timestamp, createdAt, accountNumberSuffix,
        TransactionCategory.valueOf(category), person,
        runCatching { SpendingCategory.valueOf(spendingCategory) }.getOrDefault(SpendingCategory.UNCATEGORIZED),
    )

    private fun ParsingRuleEntity.toBackup() = BackupRule(
        id, name, pattern, senderPattern, direction.name, defaultCurrency, priority, enabled, sampleText, createdAt,
        assignAccountId,
    )

    private fun BackupRule.toEntity() = ParsingRuleEntity(
        id, name, pattern, senderPattern, TransactionDirection.valueOf(direction),
        defaultCurrency, priority, enabled, sampleText, createdAt, assignAccountId,
    )
}
