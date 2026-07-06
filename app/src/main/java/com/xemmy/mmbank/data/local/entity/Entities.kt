package com.xemmy.mmbank.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.model.WalletBalance
import java.math.BigDecimal

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val currencyCode: String,
    val openingBalance: BigDecimal,
    val archived: Boolean = false,
    val notes: String? = null,
    val createdAt: Long,
    val accountNumber: String? = null,
    val bankName: String? = null,
    val accountTitle: String? = null,
    val iban: String? = null,
    val swift: String? = null,
    val outstandingBalance: BigDecimal? = null,
    val nextInstallmentAmount: BigDecimal? = null,
    val dueDayOfMonth: Int? = null,
    val profitRatePa: Double? = null,
    val maturityDateMillis: Long? = null,
    val loanNotes: String? = null,
)

@Entity(tableName = "sender_labels")
data class SenderLabelEntity(
    /** SMS originating address or notification package name, e.g. "com.adib.mobile". */
    @PrimaryKey val sender: String,
    /** User-facing name, e.g. "ADIB". */
    val label: String,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [
        // Backs the sliding-window dedup lookup.
        Index(value = ["amount", "currencyCode", "timestamp"]),
        Index(value = ["accountId"]),
        Index(value = ["timestamp"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val direction: TransactionDirection,
    val merchant: String? = null,
    val source: TransactionSource,
    val sender: String? = null,
    val rawMessage: String? = null,
    val matchedRuleName: String? = null,
    /** Event time (SMS timestamp / notification post time) — the dedup axis. */
    val timestamp: Long,
    val createdAt: Long,
    val accountNumberSuffix: String? = null,
    val category: TransactionCategory = TransactionCategory.NORMAL,
    val person: String? = null,
)

@Entity(tableName = "parsing_rules")
data class ParsingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pattern: String,
    val senderPattern: String? = null,
    val direction: TransactionDirection,
    val defaultCurrency: String? = null,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val sampleText: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "asset_balances")
data class AssetBalanceEntity(
    @PrimaryKey val asset: String,
    val free: BigDecimal,
    val locked: BigDecimal,
    val priceUsd: BigDecimal? = null,
    val valueUsd: BigDecimal? = null,
    val updatedAt: Long,
)

@Entity(tableName = "wallet_balances")
data class WalletBalanceEntity(
    /** "Spot", "Funding", "Earn", "Trading Bots", … as named by Binance. */
    @PrimaryKey val walletName: String,
    val balanceBtc: BigDecimal,
    val valueUsd: BigDecimal? = null,
    val updatedAt: Long,
)

@Entity(tableName = "exchange_rates", primaryKeys = ["baseCode", "quoteCode"])
data class ExchangeRateEntity(
    val baseCode: String,
    val quoteCode: String,
    @ColumnInfo(name = "rate") val rate: BigDecimal,
    val fetchedAt: Long,
)

// --- Entity <-> domain mappers ---

fun AccountEntity.toDomain() = Account(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
)

fun Account.toEntity() = AccountEntity(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
)

fun SenderLabelEntity.toDomain() = com.xemmy.mmbank.domain.model.SenderLabel(sender, label)
fun com.xemmy.mmbank.domain.model.SenderLabel.toEntity() = SenderLabelEntity(sender, label)

fun TransactionEntity.toDomain() = Transaction(
    id, accountId, amount, currencyCode, direction, merchant, source, sender,
    rawMessage, matchedRuleName, timestamp, createdAt, accountNumberSuffix, category, person,
)

fun ParsingRuleEntity.toDomain() = ParsingRule(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
)

fun ParsingRule.toEntity() = ParsingRuleEntity(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
)

fun AssetBalanceEntity.toDomain() = AssetBalance(asset, free, locked, priceUsd, valueUsd, updatedAt)

fun WalletBalanceEntity.toDomain() = WalletBalance(walletName, balanceBtc, valueUsd, updatedAt)

fun ExchangeRateEntity.toDomain() = ExchangeRate(baseCode, quoteCode, rate, fetchedAt)
