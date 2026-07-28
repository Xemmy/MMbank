package com.xemmy.mmbank.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
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
    /** Day of month (1-31) the card's statement cycle starts; null = no cycle. */
    val statementCycleDay: Int? = null,
    /** True for sample rows created by the Settings "Demo mode" toggle. */
    val isDemo: Boolean = false,
    /** Hides this account's card from the Dashboard's account carousel; see [Account]. */
    val hiddenFromDashboard: Boolean = false,
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
    val spendingCategory: SpendingCategory = SpendingCategory.UNCATEGORIZED,
    /** Kept in the ledger but omitted from analytics (corrections, refund adjustments, transfers). */
    val excludeFromStats: Boolean = false,
    /** The counterpart transaction this one was reconciled with (see TransferMatcher); null until matched. */
    val linkedTransactionId: Long? = null,
    /** True for sample rows created by the Settings "Demo mode" toggle. */
    val isDemo: Boolean = false,
    /** Dynamic [CategoryEntity] id assigned to this transaction; independent of [spendingCategory]. */
    val categoryId: Long? = null,
    /** True while [suggestedCategoryName] is an unconfirmed AI guess awaiting human approval. */
    val needsCategoryApproval: Boolean = false,
    val suggestedCategoryName: String? = null,
    val suggestedCategoryIcon: String? = null,
    val suggestedCategoryColorHex: String? = null,
    /** Raw merchant text as pulled from [rawMessage] at capture time; see [Transaction.extractedMerchant]. */
    val extractedMerchant: String? = null,
    /** 0-100 automated-categorization confidence; see [Transaction.aiConfidence]. */
    val aiConfidence: Int? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorHex: String,
    val isCustom: Boolean = false,
    val monthlyCap: BigDecimal? = null,
)

@Entity(
    tableName = "merchant_category_mappings",
    indices = [Index(value = ["merchantPattern"], unique = true)],
)
data class MerchantCategoryMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantPattern: String,
    val categoryId: Long? = null,
    val isExactMatch: Boolean = true,
    /** True for an unconfirmed AI category guess awaiting human approval. */
    val pending: Boolean = false,
    val suggestedName: String? = null,
    val suggestedIcon: String? = null,
    val suggestedColorHex: String? = null,
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
    val assignAccountId: Long? = null,
    /** True for sample rules created by the Settings "Demo mode" toggle. */
    val isDemo: Boolean = false,
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

@Entity(
    tableName = "subscriptions",
    indices = [Index(value = ["merchant"], unique = true)],
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val averageAmount: BigDecimal,
    val currencyCode: String,
    val cadence: SubscriptionCadence,
    val nextDueDateMillis: Long,
    val lastSeenMillis: Long,
    val occurrenceCount: Int,
    val dismissed: Boolean = false,
)

@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetAmount: BigDecimal,
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val targetDateMillis: Long? = null,
    val icon: String = "🎯",
    val createdAt: Long,
    val archived: Boolean = false,
)

// --- Entity <-> domain mappers ---

fun AccountEntity.toDomain() = Account(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    statementCycleDay, isDemo, hiddenFromDashboard,
)

fun Account.toEntity() = AccountEntity(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    statementCycleDay, isDemo, hiddenFromDashboard,
)

fun SenderLabelEntity.toDomain() = com.xemmy.mmbank.domain.model.SenderLabel(sender, label)
fun com.xemmy.mmbank.domain.model.SenderLabel.toEntity() = SenderLabelEntity(sender, label)

fun TransactionEntity.toDomain() = Transaction(
    id, accountId, amount, currencyCode, direction, merchant, source, sender,
    rawMessage, matchedRuleName, timestamp, createdAt, accountNumberSuffix, category, person,
    spendingCategory, excludeFromStats, linkedTransactionId, isDemo,
    categoryId, needsCategoryApproval, suggestedCategoryName, suggestedCategoryIcon, suggestedCategoryColorHex,
    extractedMerchant, aiConfidence,
)

fun Transaction.toEntity() = TransactionEntity(
    id = id,
    accountId = accountId,
    amount = amount,
    currencyCode = currencyCode,
    direction = direction,
    merchant = merchant,
    source = source,
    sender = sender,
    rawMessage = rawMessage,
    matchedRuleName = matchedRuleName,
    timestamp = timestampMillis,
    createdAt = createdAt,
    accountNumberSuffix = accountNumberSuffix,
    category = category,
    person = person,
    spendingCategory = spendingCategory,
    excludeFromStats = excludeFromStats,
    linkedTransactionId = linkedTransactionId,
    isDemo = isDemo,
    categoryId = categoryId,
    needsCategoryApproval = needsCategoryApproval,
    suggestedCategoryName = suggestedCategoryName,
    suggestedCategoryIcon = suggestedCategoryIcon,
    suggestedCategoryColorHex = suggestedCategoryColorHex,
    extractedMerchant = extractedMerchant,
    aiConfidence = aiConfidence,
)

fun CategoryEntity.toDomain() = Category(id, name, icon, colorHex, isCustom, monthlyCap)
fun Category.toEntity() = CategoryEntity(id, name, icon, colorHex, isCustom, monthlyCap)

fun MerchantCategoryMappingEntity.toDomain() = MerchantCategoryMapping(
    id, merchantPattern, categoryId, isExactMatch, pending, suggestedName, suggestedIcon, suggestedColorHex,
)

fun MerchantCategoryMapping.toEntity() = MerchantCategoryMappingEntity(
    id, merchantPattern, categoryId, isExactMatch, pending, suggestedName, suggestedIcon, suggestedColorHex,
)

fun ParsingRuleEntity.toDomain() = ParsingRule(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
    assignAccountId, isDemo,
)

fun ParsingRule.toEntity() = ParsingRuleEntity(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
    assignAccountId, isDemo,
)

fun AssetBalanceEntity.toDomain() = AssetBalance(asset, free, locked, priceUsd, valueUsd, updatedAt)

fun WalletBalanceEntity.toDomain() = WalletBalance(walletName, balanceBtc, valueUsd, updatedAt)

fun ExchangeRateEntity.toDomain() = ExchangeRate(baseCode, quoteCode, rate, fetchedAt)

fun SubscriptionEntity.toDomain() = Subscription(
    id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed,
)

fun Subscription.toEntity() = SubscriptionEntity(
    id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed,
)

fun SavingsGoalEntity.toDomain() = SavingsGoal(
    id, title, targetAmount, currentAmount, targetDateMillis, icon, createdAt, archived,
)

fun SavingsGoal.toEntity() = SavingsGoalEntity(
    id, title, targetAmount, currentAmount, targetDateMillis, icon, createdAt, archived,
)
