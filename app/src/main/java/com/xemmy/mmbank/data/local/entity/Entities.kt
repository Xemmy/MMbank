package com.xemmy.mmbank.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xemmy.mmbank.domain.model.MerchantAccountMapping
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AssignmentSource
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ExchangeRateOnDay
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.model.SplitType
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
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
    /** See [Account.creditLimit]. */
    val creditLimit: BigDecimal? = null,
    /** See [Account.cardEndings]. */
    val cardEndings: String? = null,
    /** See [Account.accentColor]. */
    val accentColor: Int? = null,
)

/**
 * One row of an account's change log; see [AccountChange]. No foreign key on
 * purpose: the log must outlive a deleted account, so [accountName] is a
 * snapshot. The index backs the per-account history query.
 */
@Entity(
    tableName = "account_changes",
    indices = [Index(value = ["accountId", "timestamp"])],
)
data class AccountChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val accountName: String,
    val timestamp: Long,
    val type: AccountChangeType,
    val source: AccountChangeSource,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val balanceDelta: BigDecimal? = null,
    val currencyCode: String? = null,
    val transactionId: Long? = null,
    val reason: String? = null,
    val note: String? = null,
    val isDemo: Boolean = false,
)

@Entity(tableName = "sender_labels")
data class SenderLabelEntity(
    /** SMS originating address or notification package name, e.g. "com.adib.mobile". */
    @PrimaryKey val sender: String,
    /** User-facing name, e.g. "ADIB". */
    val label: String,
)

/**
 * A message template the user asked the app to stop capturing — see
 * [com.xemmy.mmbank.domain.model.IgnoredMessagePattern]. The unique index on
 * [fingerprint] is what makes re-adding the same promo a no-op instead of a
 * second row (the DAO upserts on conflict).
 */
@Entity(
    tableName = "ignored_message_patterns",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class IgnoredMessagePatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val sample: String,
    val createdAt: Long,
)

/** See [com.xemmy.mmbank.domain.model.IgnoredKeyword]. Unique on the lowercased keyword. */
@Entity(
    tableName = "ignored_keywords",
    indices = [Index(value = ["keyword"], unique = true)],
)
data class IgnoredKeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val createdAt: Long,
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
    /** What the row is (spend, refund, transfer, card payment...); see [TransactionKind]. */
    val kind: TransactionKind = TransactionKind.defaultFor(direction),
    /** True once the user picked [kind] by hand; see [Transaction.kindUserSet]. */
    val kindUserSet: Boolean = false,
    /** Bank-printed balance in the source message; see [Transaction.reportedBalance]. */
    val reportedBalance: BigDecimal? = null,
    val reportedBalanceKind: ReportedBalanceKind? = null,
    /** Who decided [categoryId]; see [Transaction.categorySource]. */
    val categorySource: AssignmentSource = AssignmentSource.DEFAULT,
    /** Who decided [person]; see [Transaction.personSource]. */
    val personSource: AssignmentSource = AssignmentSource.DEFAULT,
    /** The purchase a refund gave money back for; see [Transaction.refundOfId]. */
    val refundOfId: Long? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorHex: String,
    val isCustom: Boolean = false,
    val monthlyCap: BigDecimal? = null,
    /** Parent category id, for a two-level hierarchy; null = top-level. */
    val parentCategoryId: Long? = null,
)

@Entity(
    tableName = "merchant_category_mappings",
    indices = [Index(value = ["merchantPattern"], unique = true)],
)
data class MerchantCategoryMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val merchantPattern: String,
    val categoryId: Long? = null,
    val isExactMatch: Boolean = true,
    /** True for an unconfirmed AI category guess awaiting human approval. */
    val pending: Boolean = false,
    val suggestedName: String? = null,
    val suggestedIcon: String? = null,
    val suggestedColorHex: String? = null,
)

/**
 * A learned merchant -> account rule. Written when the user manually assigns a
 * captured transaction to an account, and applied to later captures from the
 * same merchant once the card number in the message fails to identify one.
 *
 * `ON DELETE CASCADE` rather than a bare column: deleting an account must not
 * leave a rule pointing at a dead id. `parsing_rules.assignAccountId` has no
 * FK and needed `ParsingRuleDao.clearAssignAccountId` plus a bug fix to get the
 * same guarantee by hand.
 *
 * [merchantPattern] holds the RAW merchant, not a normalized key -- the key is
 * recomputed at match time so that changing [com.xemmy.mmbank.domain.parser.MerchantNormalizer]
 * re-keys existing rules instead of orphaning them.
 */
@Entity(
    tableName = "merchant_account_mappings",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["merchantPattern"], unique = true),
        Index(value = ["accountId"]),
    ],
)
data class MerchantAccountMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val merchantPattern: String,
    val accountId: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A learned merchant -> transaction-kind rule; see
 * [com.xemmy.mmbank.domain.model.MerchantKindMapping]. Unique on the
 * case-insensitive merchant, so re-teaching a merchant replaces its rule.
 */
@Entity(
    tableName = "merchant_kind_mappings",
    indices = [Index(value = ["merchantPattern"], unique = true)],
)
data class MerchantKindMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val merchantPattern: String,
    val kind: TransactionKind,
    val createdAt: Long = System.currentTimeMillis(),
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
    /** Kind matched transactions get; null = automatic. See [ParsingRule.assignKind]. */
    val assignKind: TransactionKind? = null,
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

/**
 * One day's closing forex rate (quote per 1 base), written on each refresh
 * for the currencies the user actually holds -- see [RateHistory]. [day] is
 * an ISO local date (yyyy-MM-dd) so it never shifts with the time zone.
 */
@Entity(tableName = "exchange_rates_history", primaryKeys = ["baseCode", "quoteCode", "day"])
data class ExchangeRateHistoryEntity(
    val baseCode: String,
    val quoteCode: String,
    val day: String,
    @ColumnInfo(name = "rate") val rate: BigDecimal,
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
    /** Prior price when the charge amount changed partway through; null when steady. */
    val previousAmount: BigDecimal? = null,
)

@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: BigDecimal,
    val currencyCode: String,
    /** Null means one-off. */
    val recurrence: BillRecurrence? = null,
    val nextDueDateMillis: Long,
    val createdAt: Long,
    /** Day of month the user picked; lets a 31st bill return to the 31st after February. Null on legacy rows. */
    val dueDayAnchor: Int? = null,
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

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val archived: Boolean = false,
)

@Entity(tableName = "split_groups")
data class SplitGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "👥",
    val createdAt: Long,
    val archived: Boolean = false,
)

/** Junction row: which friends belong to which group. "Me" is implicit, never a row here. */
@Entity(
    tableName = "split_group_members",
    primaryKeys = ["groupId", "friendId"],
    foreignKeys = [
        ForeignKey(
            entity = SplitGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FriendEntity::class,
            parentColumns = ["id"],
            childColumns = ["friendId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["friendId"])],
)
data class SplitGroupMemberEntity(val groupId: Long, val friendId: Long)

@Entity(
    tableName = "group_expenses",
    foreignKeys = [
        ForeignKey(
            entity = SplitGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"])],
)
data class GroupExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val description: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val paidByFriendId: Long? = null,
    val splitType: SplitType,
    val createdAt: Long,
)

@Entity(
    tableName = "expense_splits",
    foreignKeys = [
        ForeignKey(
            entity = GroupExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["expenseId"])],
)
data class ExpenseSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val friendId: Long? = null,
    val owedAmount: BigDecimal,
)

// --- Entity <-> domain mappers ---

fun AccountEntity.toDomain() = Account(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    statementCycleDay, isDemo, hiddenFromDashboard, creditLimit, cardEndings, accentColor,
)

fun Account.toEntity() = AccountEntity(
    id, name, type, currencyCode, openingBalance, archived, notes, createdAt, accountNumber,
    bankName, accountTitle, iban, swift,
    outstandingBalance, nextInstallmentAmount, dueDayOfMonth, profitRatePa, maturityDateMillis, loanNotes,
    statementCycleDay, isDemo, hiddenFromDashboard, creditLimit, cardEndings, accentColor,
)

fun AccountChangeEntity.toDomain() = AccountChange(
    id = id,
    accountId = accountId,
    accountName = accountName,
    timestampMillis = timestamp,
    type = type,
    source = source,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    balanceDelta = balanceDelta,
    currencyCode = currencyCode,
    transactionId = transactionId,
    reason = reason,
    note = note,
    isDemo = isDemo,
)

fun AccountChange.toEntity() = AccountChangeEntity(
    id = id,
    accountId = accountId,
    accountName = accountName,
    timestamp = timestampMillis,
    type = type,
    source = source,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    balanceDelta = balanceDelta,
    currencyCode = currencyCode,
    transactionId = transactionId,
    reason = reason,
    note = note,
    isDemo = isDemo,
)

fun SenderLabelEntity.toDomain() = com.xemmy.mmbank.domain.model.SenderLabel(sender, label)
fun com.xemmy.mmbank.domain.model.SenderLabel.toEntity() = SenderLabelEntity(sender, label)

fun IgnoredMessagePatternEntity.toDomain() = com.xemmy.mmbank.domain.model.IgnoredMessagePattern(
    id = id,
    fingerprint = fingerprint,
    sample = sample,
    createdAt = createdAt,
)

fun com.xemmy.mmbank.domain.model.IgnoredMessagePattern.toEntity() = IgnoredMessagePatternEntity(
    id = id,
    fingerprint = fingerprint,
    sample = sample,
    createdAt = createdAt,
)

fun IgnoredKeywordEntity.toDomain() = com.xemmy.mmbank.domain.model.IgnoredKeyword(
    id = id,
    keyword = keyword,
    createdAt = createdAt,
)

fun com.xemmy.mmbank.domain.model.IgnoredKeyword.toEntity() = IgnoredKeywordEntity(
    id = id,
    keyword = keyword,
    createdAt = createdAt,
)

fun TransactionEntity.toDomain() = Transaction(
    id, accountId, amount, currencyCode, direction, merchant, source, sender,
    rawMessage, matchedRuleName, timestamp, createdAt, accountNumberSuffix, category, person,
    spendingCategory, excludeFromStats, linkedTransactionId, isDemo,
    categoryId, needsCategoryApproval, suggestedCategoryName, suggestedCategoryIcon, suggestedCategoryColorHex,
    extractedMerchant, aiConfidence, kind, kindUserSet, reportedBalance, reportedBalanceKind,
    categorySource, personSource, refundOfId,
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
    kind = kind,
    kindUserSet = kindUserSet,
    reportedBalance = reportedBalance,
    reportedBalanceKind = reportedBalanceKind,
    categorySource = categorySource,
    personSource = personSource,
    refundOfId = refundOfId,
)

fun CategoryEntity.toDomain() = Category(id, name, icon, colorHex, isCustom, monthlyCap, parentCategoryId)
fun Category.toEntity() = CategoryEntity(id, name, icon, colorHex, isCustom, monthlyCap, parentCategoryId)

fun MerchantCategoryMappingEntity.toDomain() = MerchantCategoryMapping(
    id, merchantPattern, categoryId, isExactMatch, pending, suggestedName, suggestedIcon, suggestedColorHex,
)

fun MerchantCategoryMapping.toEntity() = MerchantCategoryMappingEntity(
    id, merchantPattern, categoryId, isExactMatch, pending, suggestedName, suggestedIcon, suggestedColorHex,
)

fun MerchantAccountMappingEntity.toDomain() = MerchantAccountMapping(
    id, merchantPattern, accountId, createdAt,
)

fun MerchantAccountMapping.toEntity() = MerchantAccountMappingEntity(
    id, merchantPattern, accountId, createdAt,
)

fun MerchantKindMappingEntity.toDomain() = com.xemmy.mmbank.domain.model.MerchantKindMapping(
    id, merchantPattern, kind, createdAt,
)

fun com.xemmy.mmbank.domain.model.MerchantKindMapping.toEntity() = MerchantKindMappingEntity(
    id, merchantPattern, kind, createdAt,
)

fun ParsingRuleEntity.toDomain() = ParsingRule(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
    assignAccountId, isDemo, assignKind,
)

fun ParsingRule.toEntity() = ParsingRuleEntity(
    id, name, pattern, senderPattern, direction, defaultCurrency, priority, enabled, sampleText, createdAt,
    assignAccountId, isDemo, assignKind,
)

fun AssetBalanceEntity.toDomain() = AssetBalance(asset, free, locked, priceUsd, valueUsd, updatedAt)

fun WalletBalanceEntity.toDomain() = WalletBalance(walletName, balanceBtc, valueUsd, updatedAt)

fun ExchangeRateEntity.toDomain() = ExchangeRate(baseCode, quoteCode, rate, fetchedAt)

fun ExchangeRateHistoryEntity.toDomain() = ExchangeRateOnDay(baseCode, quoteCode, java.time.LocalDate.parse(day), rate)

fun ExchangeRateOnDay.toEntity() = ExchangeRateHistoryEntity(baseCode, quoteCode, day.toString(), rate)

fun SubscriptionEntity.toDomain() = Subscription(
    id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed,
    previousAmount,
)

fun Subscription.toEntity() = SubscriptionEntity(
    id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed,
    previousAmount,
)

fun BillEntity.toDomain() = Bill(id, name, amount, currencyCode, recurrence, nextDueDateMillis, createdAt, dueDayAnchor)

fun Bill.toEntity() = BillEntity(id, name, amount, currencyCode, recurrence, nextDueDateMillis, createdAt, dueDayAnchor)

fun SavingsGoalEntity.toDomain() = SavingsGoal(
    id, title, targetAmount, currentAmount, targetDateMillis, icon, createdAt, archived,
)

fun SavingsGoal.toEntity() = SavingsGoalEntity(
    id, title, targetAmount, currentAmount, targetDateMillis, icon, createdAt, archived,
)

fun FriendEntity.toDomain() = Friend(id, name, createdAt, archived)

fun Friend.toEntity() = FriendEntity(id, name, createdAt, archived)

/** [memberFriendIds] comes from a separate join-table query -- [SplitGroupEntity] itself carries no member list. */
fun SplitGroupEntity.toDomain(memberFriendIds: List<Long>) = SplitGroup(id, name, icon, memberFriendIds, createdAt, archived)

fun SplitGroup.toEntity() = SplitGroupEntity(id, name, icon, createdAt, archived)

fun GroupExpenseEntity.toDomain() = GroupExpense(id, groupId, description, amount, currencyCode, paidByFriendId, splitType, createdAt)

fun GroupExpense.toEntity() = GroupExpenseEntity(id, groupId, description, amount, currencyCode, paidByFriendId, splitType, createdAt)

fun ExpenseSplitEntity.toDomain() = ExpenseSplit(id, expenseId, friendId, owedAmount)

fun ExpenseSplit.toEntity() = ExpenseSplitEntity(id, expenseId, friendId, owedAmount)
