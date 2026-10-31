package com.xemmy.mmbank.data.backup

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.MerchantKindMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.ledger.TransactionKindDetector
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
    /**
     * Absent (defaulted) on backups from before format v3. `isDemo` is
     * deliberately never carried: the export selects real rows only, and an
     * import restores them as real rows.
     */
    val statementCycleDay: Int? = null,
    val hiddenFromDashboard: Boolean = false,
    /** Absent (null) on backups from before format v8. */
    val creditLimit: String? = null,
    val cardEndings: String? = null,
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
    /** Dynamic category id; absent (null) on backups from before format v2. */
    val categoryId: Long? = null,
    /**
     * Everything below is absent (defaulted) on backups from before format v3.
     * Dropping [excludeFromStats] in particular silently re-admitted every
     * balance correction, refund adjustment and reconciled transfer into the
     * analytics totals on restore, so a restored ledger reported different
     * figures than the one that was backed up.
     *
     * `isDemo` is deliberately never carried, as on [BackupAccount].
     */
    val excludeFromStats: Boolean = false,
    val linkedTransactionId: Long? = null,
    val needsCategoryApproval: Boolean = false,
    val suggestedCategoryName: String? = null,
    val suggestedCategoryIcon: String? = null,
    val suggestedCategoryColorHex: String? = null,
    val extractedMerchant: String? = null,
    val aiConfidence: Int? = null,
    /**
     * Absent (null) on backups from before format v6. Such a row is
     * classified on restore exactly as the v25 -> v26 database upgrade
     * classifies it (see [TransactionKindDetector.fromLegacy]), so restoring
     * an old backup lands on the same totals as upgrading in place.
     */
    val kind: String? = null,
    val kindUserSet: Boolean = false,
    /** Absent (null) on backups from before format v9. */
    val reportedBalance: String? = null,
    val reportedBalanceKind: String? = null,
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
    /** Absent (null = automatic) on backups from before format v6. */
    val assignKind: String? = null,
)

@Serializable
data class BackupSenderLabel(val sender: String, val label: String)

@Serializable
data class BackupCategory(
    val id: Long,
    val name: String,
    val icon: String,
    val colorHex: String,
    val isCustom: Boolean,
    val monthlyCap: String?,
    val parentCategoryId: Long?,
)

@Serializable
data class BackupMerchantMapping(
    val id: Long,
    val merchantPattern: String,
    val categoryId: Long?,
    val isExactMatch: Boolean,
    val pending: Boolean,
    val suggestedName: String?,
    val suggestedIcon: String?,
    val suggestedColorHex: String?,
)

@Serializable
data class BackupPayload(
    /**
     * 1 = original format (no category data at all). 2 = adds [categories]/
     * [merchantCategoryMappings] and [BackupTransaction.categoryId]. Import
     * gates on this: a v1 backup must never wipe categories the user built
     * up since upgrading -- see [BackupManager.import].
     *
     * 3 = carries the transaction and account columns v1/v2 silently dropped
     * (analytics exclusion, transfer links, AI review fields, statement cycle
     * day, dashboard hiding). No import gate is needed for these: every added
     * field defaults to the entity's own default, so an older backup restores
     * exactly as it did before.
     *
     * 4 = adds [ignoredMessagePatterns]. Gated like the category data: a
     * pre-v4 backup never held an ignore list, so restoring one must leave the
     * list on this device alone rather than silently un-muting everything.
     *
     * 5 = adds [merchantAccountMappings], the learned merchant -> account
     * rules. Gated the same way: a pre-v5 backup never held them, so restoring
     * one must not erase what this device has learned.
     *
     * 6 = adds [BackupTransaction.kind]/[BackupTransaction.kindUserSet] and
     * [BackupRule.assignKind]. No gate: an older row's kind is derived on
     * restore (see [BackupTransaction.toEntity]).
     *
     * 7 = adds [merchantKindMappings], the learned merchant -> kind rules.
     * Gated like the account rules: a pre-v7 backup never held them.
     *
     * 8 = adds [BackupAccount.creditLimit]/[BackupAccount.cardEndings]. No
     * gate: both default to "not set".
     *
     * 9 = adds [BackupTransaction.reportedBalance]/[BackupTransaction.reportedBalanceKind].
     * No gate: an older row simply has no bank-reported balance.
     */
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
    val categories: List<BackupCategory> = emptyList(),
    val merchantCategoryMappings: List<BackupMerchantMapping> = emptyList(),
    val ignoredMessagePatterns: List<BackupIgnoredMessagePattern> = emptyList(),
    val merchantAccountMappings: List<BackupMerchantAccountMapping> = emptyList(),
    val merchantKindMappings: List<BackupMerchantKindMapping> = emptyList(),
)

/** A learned merchant -> kind rule; see [BackupPayload.formatVersion] 7. */
@Serializable
data class BackupMerchantKindMapping(
    val id: Long,
    val merchantPattern: String,
    val kind: String,
    val createdAt: Long,
)

/** A learned merchant -> account rule; see [BackupPayload.formatVersion] 5. */
@Serializable
data class BackupMerchantAccountMapping(
    val id: Long,
    val merchantPattern: String,
    val accountId: Long,
    val createdAt: Long,
)

@Serializable
data class BackupIgnoredMessagePattern(
    val id: Long,
    val fingerprint: String,
    val sample: String,
    val createdAt: Long,
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
            formatVersion = 9,
            exportedAtMillis = System.currentTimeMillis(),
            defaultCurrency = preferences.defaultCurrency,
            cashbackSpendRaw = preferences.cashbackSpendRaw,
            personsRaw = preferences.personsRaw,
            accounts = database.accountDao().getAll().map { it.toBackup() },
            transactions = database.transactionDao().getAll().map { it.toBackup() },
            rules = database.parsingRuleDao().getAll().map { it.toBackup() },
            senderLabels = database.senderLabelDao().getAll().map { BackupSenderLabel(it.sender, it.label) },
            categories = database.categoryDao().getAll().map { it.toBackup() },
            merchantCategoryMappings = database.merchantCategoryDao().getAll().map { it.toBackup() },
            ignoredMessagePatterns = database.ignoredMessagePatternDao().getAll().map { it.toBackup() },
            merchantAccountMappings = database.merchantAccountDao().getAll().map { it.toBackup() },
            merchantKindMappings = database.merchantKindDao().getAll().map { it.toBackup() },
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
        // A v1 backup (formatVersion < 2) never captured categories or
        // merchant mappings in the first place -- restoring one must leave
        // whatever the user has built up since upgrading untouched, not wipe
        // it for zero benefit. Every other table always fully replaces, same
        // as before.
        val hasCategoryData = payload.formatVersion >= 2
        // Same reasoning one version later: a pre-v4 backup carries no ignore
        // list, so restoring it must not un-mute what this device is muting.
        val hasIgnoreList = payload.formatVersion >= 4
        // And again for the learned merchant -> account rules.
        val hasAccountMappings = payload.formatVersion >= 5
        // And for the learned merchant -> kind rules.
        val hasKindMappings = payload.formatVersion >= 7
        database.withTransaction {
            database.transactionDao().clearAll()
            database.accountDao().clearAll()
            database.parsingRuleDao().clearAll()
            database.senderLabelDao().clearAll()

            database.accountDao().insertAll(payload.accounts.map { it.toEntity() })
            // Only consulted for pre-v6 rows, which carry no kind: a reconciled
            // leg on (or paired with) a credit card was a card payment.
            val cardAccountIds = payload.accounts.filter { it.type == AccountType.CREDIT_CARD.name }.map { it.id }.toSet()
            val accountOfTransaction = payload.transactions.associate { it.id to it.accountId }
            database.transactionDao().insertAll(
                payload.transactions.map { t ->
                    t.toEntity(
                        onCreditCard = t.accountId in cardAccountIds ||
                            t.linkedTransactionId?.let { accountOfTransaction[it] } in cardAccountIds,
                    )
                }
            )
            database.parsingRuleDao().insertAll(payload.rules.map { it.toEntity() })
            database.senderLabelDao().insertAll(
                payload.senderLabels.map { SenderLabelEntity(it.sender, it.label) }
            )

            if (hasCategoryData) {
                database.categoryDao().clearAll()
                database.merchantCategoryDao().clearAll()
                // Original ids are preserved (same pattern as accounts/transactions
                // above) so the just-restored transactions' categoryId references
                // stay valid with no remapping.
                database.categoryDao().insertAll(payload.categories.map { it.toEntity() })
                database.merchantCategoryDao().insertAll(payload.merchantCategoryMappings.map { it.toEntity() })
            }

            if (hasIgnoreList) {
                database.ignoredMessagePatternDao().clearAll()
                database.ignoredMessagePatternDao().insertAll(
                    payload.ignoredMessagePatterns.map { it.toEntity() }
                )
            }

            if (hasAccountMappings) {
                // After accountDao().insertAll above, necessarily: these rows
                // carry a cascading FK to accounts, so the parents have to
                // exist first or every insert is rejected.
                database.merchantAccountDao().clearAll()
                database.merchantAccountDao().insertAll(
                    payload.merchantAccountMappings.map { it.toEntity() }
                )
            }

            if (hasKindMappings) {
                database.merchantKindDao().clearAll()
                database.merchantKindDao().insertAll(
                    payload.merchantKindMappings.mapNotNull { it.toEntity() }
                )
            }
        }
        preferences.defaultCurrency = payload.defaultCurrency
        preferences.cashbackSpendRaw = payload.cashbackSpendRaw
        preferences.personsRaw = payload.personsRaw
        return payload.accounts.size to payload.transactions.size
    }
}

// --- entity <-> backup mappers ---
//
// Top-level and internal rather than private methods on BackupManager, so the
// round trip is JVM-testable without a Room database -- the same stance the
// entity <-> domain mappers take (see EntityMappersTest).
//
// Every argument is named. These pair a DTO with an entity that has a similar
// but not identical field order, several runs of same-typed nullable columns,
// and defaults on both sides: positional arguments here would let a field
// added or moved in either class compile clean and silently write the wrong
// column, which is exactly how the dropped fields below went unnoticed.

internal fun AccountEntity.toBackup() = BackupAccount(
    id = id,
    name = name,
    type = type.name,
    currencyCode = currencyCode,
    openingBalance = openingBalance.toPlainString(),
    archived = archived,
    notes = notes,
    createdAt = createdAt,
    accountNumber = accountNumber,
    bankName = bankName,
    accountTitle = accountTitle,
    iban = iban,
    swift = swift,
    outstandingBalance = outstandingBalance?.toPlainString(),
    nextInstallmentAmount = nextInstallmentAmount?.toPlainString(),
    dueDayOfMonth = dueDayOfMonth,
    profitRatePa = profitRatePa,
    maturityDateMillis = maturityDateMillis,
    loanNotes = loanNotes,
    statementCycleDay = statementCycleDay,
    hiddenFromDashboard = hiddenFromDashboard,
    creditLimit = creditLimit?.toPlainString(),
    cardEndings = cardEndings,
)

internal fun BackupAccount.toEntity() = AccountEntity(
    id = id,
    name = name,
    type = AccountType.valueOf(type),
    currencyCode = currencyCode,
    openingBalance = BigDecimal(openingBalance),
    archived = archived,
    notes = notes,
    createdAt = createdAt,
    accountNumber = accountNumber,
    bankName = bankName,
    accountTitle = accountTitle,
    iban = iban,
    swift = swift,
    outstandingBalance = outstandingBalance?.let(::BigDecimal),
    nextInstallmentAmount = nextInstallmentAmount?.let(::BigDecimal),
    dueDayOfMonth = dueDayOfMonth,
    profitRatePa = profitRatePa,
    maturityDateMillis = maturityDateMillis,
    loanNotes = loanNotes,
    statementCycleDay = statementCycleDay,
    hiddenFromDashboard = hiddenFromDashboard,
    creditLimit = creditLimit?.let(::BigDecimal),
    cardEndings = cardEndings,
)

internal fun TransactionEntity.toBackup() = BackupTransaction(
    id = id,
    accountId = accountId,
    amount = amount.toPlainString(),
    currencyCode = currencyCode,
    direction = direction.name,
    merchant = merchant,
    source = source.name,
    sender = sender,
    rawMessage = rawMessage,
    matchedRuleName = matchedRuleName,
    timestamp = timestamp,
    createdAt = createdAt,
    accountNumberSuffix = accountNumberSuffix,
    category = category.name,
    person = person,
    spendingCategory = spendingCategory.name,
    categoryId = categoryId,
    excludeFromStats = excludeFromStats,
    linkedTransactionId = linkedTransactionId,
    needsCategoryApproval = needsCategoryApproval,
    suggestedCategoryName = suggestedCategoryName,
    suggestedCategoryIcon = suggestedCategoryIcon,
    suggestedCategoryColorHex = suggestedCategoryColorHex,
    extractedMerchant = extractedMerchant,
    aiConfidence = aiConfidence,
    kind = kind.name,
    kindUserSet = kindUserSet,
    reportedBalance = reportedBalance?.toPlainString(),
    reportedBalanceKind = reportedBalanceKind?.name,
)

/**
 * [onCreditCard] only matters for a pre-v6 row (null [BackupTransaction.kind]):
 * whether it, or the leg it was reconciled with, sits on a credit card.
 */
internal fun BackupTransaction.toEntity(onCreditCard: Boolean = false): TransactionEntity {
    val dir = TransactionDirection.valueOf(direction)
    val txCategory = TransactionCategory.valueOf(category)
    val (restoredKind, restoredExclude) = if (kind != null) {
        TransactionKind.parse(kind, dir) to excludeFromStats
    } else {
        TransactionKindDetector.fromLegacy(
            direction = dir,
            matchedRuleName = matchedRuleName,
            merchant = merchant,
            category = txCategory,
            excludeFromStats = excludeFromStats,
            linked = linkedTransactionId != null,
            onCreditCard = onCreditCard,
        )
    }
    return TransactionEntity(
        id = id,
        accountId = accountId,
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = dir,
        merchant = merchant,
        source = TransactionSource.valueOf(source),
        sender = sender,
        rawMessage = rawMessage,
        matchedRuleName = matchedRuleName,
        timestamp = timestamp,
        createdAt = createdAt,
        accountNumberSuffix = accountNumberSuffix,
        category = txCategory,
        person = person,
        // An unknown category name is tolerated (a backup written by a newer build
        // than the one importing it); the enum's own fallback, as at capture time.
        spendingCategory = runCatching { SpendingCategory.valueOf(spendingCategory) }
            .getOrDefault(SpendingCategory.UNCATEGORIZED),
        categoryId = categoryId,
        excludeFromStats = restoredExclude,
        linkedTransactionId = linkedTransactionId,
        needsCategoryApproval = needsCategoryApproval,
        suggestedCategoryName = suggestedCategoryName,
        suggestedCategoryIcon = suggestedCategoryIcon,
        suggestedCategoryColorHex = suggestedCategoryColorHex,
        extractedMerchant = extractedMerchant,
        aiConfidence = aiConfidence,
        kind = restoredKind,
        kindUserSet = kindUserSet,
        reportedBalance = reportedBalance?.let { runCatching { BigDecimal(it) }.getOrNull() },
        reportedBalanceKind = reportedBalanceKind?.let { n -> ReportedBalanceKind.entries.firstOrNull { it.name == n } },
    )
}

internal fun ParsingRuleEntity.toBackup() = BackupRule(
    id = id,
    name = name,
    pattern = pattern,
    senderPattern = senderPattern,
    direction = direction.name,
    defaultCurrency = defaultCurrency,
    priority = priority,
    enabled = enabled,
    sampleText = sampleText,
    createdAt = createdAt,
    assignAccountId = assignAccountId,
    assignKind = assignKind?.name,
)

internal fun BackupRule.toEntity() = ParsingRuleEntity(
    id = id,
    name = name,
    pattern = pattern,
    senderPattern = senderPattern,
    direction = TransactionDirection.valueOf(direction),
    defaultCurrency = defaultCurrency,
    priority = priority,
    enabled = enabled,
    sampleText = sampleText,
    createdAt = createdAt,
    assignAccountId = assignAccountId,
    assignKind = assignKind?.let { n -> TransactionKind.entries.firstOrNull { it.name == n } },
)

internal fun CategoryEntity.toBackup() = BackupCategory(
    id = id,
    name = name,
    icon = icon,
    colorHex = colorHex,
    isCustom = isCustom,
    monthlyCap = monthlyCap?.toPlainString(),
    parentCategoryId = parentCategoryId,
)

internal fun BackupCategory.toEntity() = CategoryEntity(
    id = id,
    name = name,
    icon = icon,
    colorHex = colorHex,
    isCustom = isCustom,
    monthlyCap = monthlyCap?.let(::BigDecimal),
    parentCategoryId = parentCategoryId,
)

internal fun MerchantAccountMappingEntity.toBackup() = BackupMerchantAccountMapping(
    id = id,
    merchantPattern = merchantPattern,
    accountId = accountId,
    createdAt = createdAt,
)

internal fun BackupMerchantAccountMapping.toEntity() = MerchantAccountMappingEntity(
    id = id,
    merchantPattern = merchantPattern,
    accountId = accountId,
    createdAt = createdAt,
)

internal fun MerchantKindMappingEntity.toBackup() = BackupMerchantKindMapping(
    id = id,
    merchantPattern = merchantPattern,
    kind = kind.name,
    createdAt = createdAt,
)

/** Null for a kind this build doesn't know (a backup from a newer build): that rule is skipped. */
internal fun BackupMerchantKindMapping.toEntity(): MerchantKindMappingEntity? =
    TransactionKind.entries.firstOrNull { it.name == kind }?.let { known ->
        MerchantKindMappingEntity(
            id = id,
            merchantPattern = merchantPattern,
            kind = known,
            createdAt = createdAt,
        )
    }

internal fun MerchantCategoryMappingEntity.toBackup() = BackupMerchantMapping(
    id = id,
    merchantPattern = merchantPattern,
    categoryId = categoryId,
    isExactMatch = isExactMatch,
    pending = pending,
    suggestedName = suggestedName,
    suggestedIcon = suggestedIcon,
    suggestedColorHex = suggestedColorHex,
)

internal fun BackupMerchantMapping.toEntity() = MerchantCategoryMappingEntity(
    id = id,
    merchantPattern = merchantPattern,
    categoryId = categoryId,
    isExactMatch = isExactMatch,
    pending = pending,
    suggestedName = suggestedName,
    suggestedIcon = suggestedIcon,
    suggestedColorHex = suggestedColorHex,
)

internal fun IgnoredMessagePatternEntity.toBackup() = BackupIgnoredMessagePattern(
    id = id,
    fingerprint = fingerprint,
    sample = sample,
    createdAt = createdAt,
)

internal fun BackupIgnoredMessagePattern.toEntity() = IgnoredMessagePatternEntity(
    id = id,
    fingerprint = fingerprint,
    sample = sample,
    createdAt = createdAt,
)
