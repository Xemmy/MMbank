package com.xemmy.mmbank.data.backup

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.AccountChangeEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.IgnoredKeywordEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.MerchantKindMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.PayLaterInstallmentEntity
import com.xemmy.mmbank.data.local.entity.PayLaterPlanEntity
import com.xemmy.mmbank.data.local.entity.SubscriptionEntity
import com.xemmy.mmbank.data.local.entity.SubscriptionLinkEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupEntity
import com.xemmy.mmbank.data.local.entity.LabelEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupMemberEntity
import com.xemmy.mmbank.data.local.entity.TransactionLabelEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.domain.backup.BackupScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xemmy.mmbank.domain.account.AccountChangeDiff
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssignmentSource
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.intelligence.SubscriptionRescan
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SplitType
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
    /** Absent (null) on backups from before accent colours; no format gate needed. */
    val accentColor: Int? = null,
    /** Format 19. Absent on older backups: position 0, counted in net worth. */
    val sortOrder: Int = 0,
    val excludeFromTotals: Boolean = false,
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
    /**
     * Who decided the category / person (an [AssignmentSource] name). Absent
     * (null) on backups from before format v12: such a row restores as `USER`
     * when it has a category / person, exactly as the v33 -> v34 database
     * upgrade marks existing rows, so the app never re-assigns old history.
     */
    val categorySource: String? = null,
    val personSource: String? = null,
    /** Absent (null) on backups from before format v13. */
    val refundOfId: Long? = null,
    /** Absent (false) on backups from before format v15. */
    val historyOnly: Boolean = false,
    /** Format 17: the user's note. Absent on older backups. */
    val note: String? = null,
    /** Format 24: bill line from a provider SMS. Absent on older backups. */
    val billRef: String? = null,
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
    /** Format 18. Absent on older backups: the category keeps its place in the list. */
    val sortOrder: Int = 0,
    val flow: String = "EXPENSE",
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
     * 14 = adds [accountChanges], the per-account change log. Always replaced on
     * restore (log rows are keyed to account ids, which a restore re-issues), so an
     * older backup simply restores an empty log -- plus one marker per account.
     *
     * 19 = adds [BackupAccount.sortOrder] and [BackupAccount.excludeFromTotals]. No gate: absent means
     * position 0 and counted in net worth.
     *
     * 18 = adds [BackupCategory.sortOrder] and [BackupCategory.flow]. No gate: an older category keeps
     * its backup position and counts as an expense category.
     *
     * 17 = adds [BackupTransaction.note], [labels] and [transactionLabels]. Labels are gated on the
     * version: an older backup has none, so a restore leaves no labels.
     *
     * 16 = adds [inactivePersonsRaw]. No gate: absent means everyone is active.
     *
     * 15 = adds [BackupTransaction.historyOnly]. No gate: absent means false.
     *
     * 13 = adds [BackupTransaction.refundOfId]. No gate: absent means no link.
     *
     * 12 = adds [BackupTransaction.categorySource]/[BackupTransaction.personSource].
     * No gate: an older row's sources are derived on restore.
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
     *
     * 10 = adds [ignoredKeywords]. Gated like the ignore list: a pre-v10
     * backup never held them, so restoring one must leave this device's alone.
     *
     * 11 = adds [extras]: bills, goals, friends, group splits and the goal/budget
     * preferences. Null on older backups, which then leave those alone.
     */
    val formatVersion: Int = 1,
    val exportedAtMillis: Long,
    val defaultCurrency: String,
    val cashbackSpendRaw: String,
    /** Saved people list (newline-separated); default keeps old backups importable. */
    val personsRaw: String = "",
    /** Saved people marked inactive (newline-separated); absent on backups from before format v16 = everyone active. */
    val inactivePersonsRaw: String = "",
    val accounts: List<BackupAccount>,
    val transactions: List<BackupTransaction>,
    val rules: List<BackupRule>,
    val senderLabels: List<BackupSenderLabel>,
    val categories: List<BackupCategory> = emptyList(),
    val merchantCategoryMappings: List<BackupMerchantMapping> = emptyList(),
    val ignoredMessagePatterns: List<BackupIgnoredMessagePattern> = emptyList(),
    val merchantAccountMappings: List<BackupMerchantAccountMapping> = emptyList(),
    val merchantKindMappings: List<BackupMerchantKindMapping> = emptyList(),
    val ignoredKeywords: List<BackupIgnoredKeyword> = emptyList(),
    /** Format 22: "also rename future messages" rules. */
    val merchantAliases: List<BackupMerchantAlias> = emptyList(),
    val extras: BackupExtras? = null,
    val accountChanges: List<BackupAccountChange> = emptyList(),
    /** Format 17: labels and which transactions carry them. */
    val labels: List<BackupLabel> = emptyList(),
    val transactionLabels: List<BackupTransactionLabel> = emptyList(),
)

@Serializable
data class BackupLabel(val id: Long, val name: String, val colorHex: String)

@Serializable
data class BackupTransactionLabel(val transactionId: Long, val labelId: Long)

/**
 * Format 11: bills, savings goals, friends and group splits, plus the goal and
 * budget preferences. Null on older backups, which then leave these alone.
 */
@Serializable
data class BackupExtras(
    val bills: List<BackupBill> = emptyList(),
    val goals: List<BackupGoal> = emptyList(),
    val friends: List<BackupFriend> = emptyList(),
    val groups: List<BackupGroup> = emptyList(),
    val groupMembers: List<BackupGroupMember> = emptyList(),
    val groupExpenses: List<BackupGroupExpense> = emptyList(),
    val expenseSplits: List<BackupExpenseSplit> = emptyList(),
    val payLaterPlans: List<BackupPayLaterPlan> = emptyList(),
    val payLaterInstallments: List<BackupPayLaterInstallment> = emptyList(),
    val manualSubscriptions: List<BackupManualSubscription> = emptyList(),
    val subscriptionLinks: List<BackupSubscriptionLink> = emptyList(),
    val goalTargetAmount: Double? = null,
    val goalMonthlySaving: Double? = null,
    val monthlySpendBudget: Double? = null,
    val fixedMonthlyExpenses: Double? = null,
)

/** A hand-added subscription; detected ones are rebuilt by a scan, so only manual rows are backed up. */
@Serializable
data class BackupManualSubscription(
    val id: Long, val merchant: String, val averageAmount: String, val currencyCode: String,
    val cadence: String, val nextDueDateMillis: Long, val lastSeenMillis: Long, val occurrenceCount: Int,
    val dismissed: Boolean = false,
    val lines: Int = 1,
)

/**
 * Links name the subscription row by id ([subscriptionId], format 20). A backup from before that
 * carries only [merchant] and is resolved by merchant on restore (see [resolveBackupLinks]).
 */
@Serializable
data class BackupSubscriptionLink(val transactionId: Long, val merchant: String = "", val subscriptionId: Long = 0)

/** The id-based links to restore: old merchant-only links are matched to the restored manual rows by merchant. */
internal fun resolveBackupLinks(
    links: List<BackupSubscriptionLink>,
    manual: List<BackupManualSubscription>,
): List<SubscriptionLinkEntity> {
    val ids = manual.map { it.id }.toSet()
    val byMerchant = manual.groupBy { it.merchant }.mapValues { it.value.first().id }
    return links.mapNotNull { link ->
        val id = if (link.subscriptionId != 0L) link.subscriptionId else byMerchant[link.merchant]
        id?.takeIf { it in ids }?.let { SubscriptionLinkEntity(link.transactionId, it) }
    }
}

@Serializable
data class BackupPayLaterPlan(
    val id: Long, val merchant: String, val provider: String, val totalAmount: String,
    val currencyCode: String, val installmentCount: Int, val cadence: String,
    val payFromAccountId: Long?, val payToAccountId: Long?, val sourceTransactionId: Long?,
    val categoryId: Long?, val note: String?, val createdAt: Long, val isDemo: Boolean,
    val sourceIsInstallment: Boolean = false,
)

@Serializable
data class BackupPayLaterInstallment(
    val id: Long, val planId: Long, val number: Int, val amount: String,
    val dueDateMillis: Long, val paidAtMillis: Long?, val paymentTransactionId: Long?,
)

@Serializable
data class BackupBill(
    val id: Long, val name: String, val amount: String, val currencyCode: String,
    val recurrence: String?, val nextDueDateMillis: Long, val createdAt: Long,
    val dueDayAnchor: Int? = null,
    val providerKey: String? = null,
    val accountRef: String? = null,
    val lastPaidMillis: Long? = null,
)

@Serializable
data class BackupGoal(
    val id: Long, val title: String, val targetAmount: String, val currentAmount: String,
    val targetDateMillis: Long?, val icon: String, val createdAt: Long, val archived: Boolean,
)

@Serializable
data class BackupFriend(val id: Long, val name: String, val createdAt: Long, val archived: Boolean)

@Serializable
data class BackupGroup(val id: Long, val name: String, val icon: String, val createdAt: Long, val archived: Boolean)

@Serializable
data class BackupGroupMember(val groupId: Long, val friendId: Long)

@Serializable
data class BackupGroupExpense(
    val id: Long, val groupId: Long, val description: String, val amount: String, val currencyCode: String,
    val paidByFriendId: Long?, val splitType: String, val createdAt: Long,
)

@Serializable
data class BackupExpenseSplit(val id: Long, val expenseId: Long, val friendId: Long?, val owedAmount: String)

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

/** One account change-log entry; see [BackupPayload.formatVersion] 14. `isDemo` is never carried, as on [BackupAccount]. */
@Serializable
data class BackupAccountChange(
    val id: Long,
    val accountId: Long,
    val accountName: String,
    val timestamp: Long,
    val type: String,
    val source: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val balanceDelta: String? = null,
    val currencyCode: String? = null,
    val transactionId: Long? = null,
    val reason: String? = null,
    val note: String? = null,
)

@Serializable
data class BackupMerchantAlias(val fromKey: String, val toName: String)

@Serializable
data class BackupIgnoredKeyword(
    val id: Long,
    val keyword: String,
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

    /**
     * Off the main thread: key derivation, AES and the JSON of the whole ledger are CPU work
     * that froze the UI on a large ledger. Only real rows are exported, and every reference to
     * a row outside the export is dropped (see [BackupScope]) so the file always restores.
     */
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val accountRows = database.accountDao().getAll()
        val accountIds = accountRows.mapTo(HashSet()) { it.id }
        val transactionRows = database.transactionDao().getAll()
        val transactionIds = transactionRows.mapTo(HashSet()) { it.id }
        val payload = BackupPayload(
            formatVersion = 24,
            exportedAtMillis = System.currentTimeMillis(),
            defaultCurrency = preferences.defaultCurrency,
            cashbackSpendRaw = preferences.cashbackSpendRaw,
            personsRaw = BackupScope.personsRaw(preferences.demoModeEnabled, preferences.personsRaw, preferences.demoPersonsBackup),
            inactivePersonsRaw = preferences.inactivePersonsRaw,
            accounts = accountRows.map { it.toBackup() },
            transactions = transactionRows.map { row ->
                row.toBackup().let { it.copy(accountId = BackupScope.exportedAccountOrNull(it.accountId, accountIds)) }
            },
            rules = database.parsingRuleDao().getAll().map { it.toBackup() },
            senderLabels = database.senderLabelDao().getAll().map { BackupSenderLabel(it.sender, it.label) },
            categories = database.categoryDao().getAll().map { it.toBackup() },
            merchantCategoryMappings = database.merchantCategoryDao().getAll().map { it.toBackup() },
            ignoredMessagePatterns = database.ignoredMessagePatternDao().getAll().map { it.toBackup() },
            merchantAccountMappings = BackupScope.referencingExported(database.merchantAccountDao().getAll(), accountIds) { it.accountId }
                .map { it.toBackup() },
            merchantKindMappings = database.merchantKindDao().getAll().map { it.toBackup() },
            ignoredKeywords = database.ignoredKeywordDao().getAll().map { it.toBackup() },
            merchantAliases = database.merchantAliasDao().getAll().map { BackupMerchantAlias(it.fromKey, it.toName) },
            accountChanges = database.accountChangeDao().getAll().map { it.toBackup() },
            labels = database.labelDao().getAll().map { BackupLabel(it.id, it.name, it.colorHex) },
            transactionLabels = BackupScope.referencingExported(database.labelDao().getAllLinks(), transactionIds) { it.transactionId }
                .map { BackupTransactionLabel(it.transactionId, it.labelId) },
            extras = BackupExtras(
                bills = database.billDao().getAll().map { it.toBackup() },
                payLaterPlans = database.payLaterDao().getAllPlans().map { it.toBackup() },
                payLaterInstallments = database.payLaterDao().getAllInstallments().map { it.toBackup() },
                manualSubscriptions = database.subscriptionDao().getManual().map { it.toBackup() },
                subscriptionLinks = BackupScope.referencingExported(database.subscriptionDao().getAllLinks(), transactionIds) { it.transactionId }
                    .map { BackupSubscriptionLink(it.transactionId, subscriptionId = it.subscriptionId) },
                goals = database.savingsGoalDao().getAll().map { it.toBackup() },
                friends = database.friendDao().getAll().map { it.toBackup() },
                groups = database.splitGroupDao().getAll().map { it.toBackup() },
                groupMembers = database.splitGroupDao().getAllMembers().map { BackupGroupMember(it.groupId, it.friendId) },
                groupExpenses = database.groupExpenseDao().getAll().map { it.toBackup() },
                expenseSplits = database.groupExpenseDao().getAllSplits().map { it.toBackup() },
                goalTargetAmount = preferences.goalTargetAmount,
                goalMonthlySaving = preferences.goalMonthlySaving,
                monthlySpendBudget = preferences.monthlySpendBudget,
                fixedMonthlyExpenses = preferences.fixedMonthlyExpenses,
            ),
        )
        BackupCrypto.encrypt(json.encodeToString(payload).toByteArray(Charsets.UTF_8), password)
    }

    /**
     * Exports every transaction as a plain, UTF-8 CSV for spreadsheets. Unlike
     * [export] this is NOT encrypted — it carries no rule patterns or settings,
     * just the ledger rows, with account ids resolved to names. RFC-4180 quoting.
     */
    suspend fun exportCsv(): String = withContext(Dispatchers.Default) {
        val accountNames = database.accountDao().getAll().associate { it.id to it.name }
        val categoryNames = database.categoryDao().getAll().associate { it.id to it.name }
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
                // The category the user filed it under (categoryId), not the capture-time guess.
                BackupScope.csvCategory(t.categoryId, categoryNames),
                t.accountId?.let { accountNames[it] }.orEmpty(),
                t.person.orEmpty(),
                t.source.name,
                t.matchedRuleName.orEmpty(),
                t.sender.orEmpty(),
            )
            sb.append(row.joinToString(",", transform = ::csvEscape)).append("\r\n")
        }
        sb.toString()
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    /** @return counts (accounts, transactions) for the status message. */
    suspend fun import(blob: ByteArray, password: CharArray): Pair<Int, Int> {
        // Key derivation, AES and JSON parsing off the main thread.
        val payload = withContext(Dispatchers.Default) {
            json.decodeFromString<BackupPayload>(BackupCrypto.decrypt(blob, password).toString(Charsets.UTF_8))
        }
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
        val hasIgnoredKeywords = payload.formatVersion >= 10
        val extras = payload.extras
        database.withTransaction {
            database.transactionDao().clearAll()
            database.accountDao().clearAll()
            database.parsingRuleDao().clearAll()
            database.senderLabelDao().clearAll()

            database.accountDao().insertAll(payload.accounts.map { it.toEntity() })
            // The change log is keyed to account ids, which a restore re-issues, so it is always replaced:
            // the backup's own log (format 14+), then one marker per account saying where it came from.
            database.accountChangeDao().clearAll()
            if (payload.formatVersion >= 14) {
                database.accountChangeDao().insertAll(payload.accountChanges.map { it.toEntity() })
            }
            val restoredAt = System.currentTimeMillis()
            database.accountChangeDao().insertAll(
                payload.accounts.map { AccountChangeDiff.backupRestored(it.toEntity().toDomain(), restoredAt).toEntity() },
            )
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

            // Format 17+ carries labels. Clearing the transactions above already dropped every link
            // (cascade); an older backup has none, so labels from before the restore are left to
            // their now-empty state rather than guessed at.
            if (payload.formatVersion >= 17) {
                database.labelDao().clearAll()
                database.labelDao().insertAll(payload.labels.map { LabelEntity(it.id, it.name, it.colorHex) })
                database.labelDao().insertLinks(payload.transactionLabels.map { TransactionLabelEntity(it.transactionId, it.labelId) })
            }

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

            if (payload.formatVersion >= 22) {
                database.merchantAliasDao().clearAll()
                database.merchantAliasDao().insertAll(
                    payload.merchantAliases.map { com.xemmy.mmbank.data.local.entity.MerchantAliasEntity(it.fromKey, it.toName) },
                )
            }

            if (hasIgnoredKeywords) {
                database.ignoredKeywordDao().clearAll()
                database.ignoredKeywordDao().insertAll(payload.ignoredKeywords.map { it.toEntity() })
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

            if (extras != null) {
                // Friends and groups first: members, expenses and splits cascade
                // from them, so clearing the parents empties the children too.
                database.friendDao().clearAll()
                database.splitGroupDao().clearAll()
                database.billDao().clearAll()
                database.savingsGoalDao().clearAll()
                database.billDao().insertAll(extras.bills.map { it.toEntity() })
                database.payLaterDao().clearAll()
                database.payLaterDao().insertPlans(extras.payLaterPlans.map { it.toEntity() })
                database.payLaterDao().insertInstallments(extras.payLaterInstallments.map { it.toEntity() })
                database.subscriptionDao().deleteManual()
                database.subscriptionDao().clearLinks()
                extras.manualSubscriptions.forEach { database.subscriptionDao().upsert(it.toEntity()) }
                database.subscriptionDao().insertLinks(
                    resolveBackupLinks(extras.subscriptionLinks, extras.manualSubscriptions),
                )
                database.savingsGoalDao().insertAll(extras.goals.map { it.toEntity() })
                database.friendDao().insertAll(extras.friends.map { it.toEntity() })
                database.splitGroupDao().insertAll(extras.groups.map { it.toEntity() })
                database.splitGroupDao().insertMembers(
                    extras.groupMembers.map { SplitGroupMemberEntity(it.groupId, it.friendId) }
                )
                database.groupExpenseDao().insertAll(extras.groupExpenses.map { it.toEntity() })
                database.groupExpenseDao().insertSplits(extras.expenseSplits.map { it.toEntity() })
            }
        }
        preferences.defaultCurrency = payload.defaultCurrency
        preferences.cashbackSpendRaw = payload.cashbackSpendRaw
        preferences.personsRaw = payload.personsRaw
        preferences.inactivePersonsRaw = payload.inactivePersonsRaw
        extras?.let {
            it.goalTargetAmount?.let { v -> preferences.goalTargetAmount = v }
            it.goalMonthlySaving?.let { v -> preferences.goalMonthlySaving = v }
            it.monthlySpendBudget?.let { v -> preferences.monthlySpendBudget = v }
            it.fixedMonthlyExpenses?.let { v -> preferences.fixedMonthlyExpenses = v }
        }
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
    accentColor = accentColor,
    sortOrder = sortOrder,
    excludeFromTotals = excludeFromTotals,
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
    accentColor = accentColor,
    sortOrder = sortOrder,
    excludeFromTotals = excludeFromTotals,
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
    categorySource = categorySource.name,
    personSource = personSource.name,
    refundOfId = refundOfId,
    historyOnly = historyOnly,
    note = note,
    billRef = billRef,
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
        categorySource = restoreSource(categorySource, hasValue = categoryId != null),
        personSource = restoreSource(personSource, hasValue = !person.isNullOrBlank()),
        refundOfId = refundOfId,
        // The flag only means something on a row with no account.
        historyOnly = historyOnly && accountId == null,
        note = note,
        billRef = billRef,
    )
}

/** A recorded source, or -- for a pre-v12 backup -- `USER` if the row has a value and `DEFAULT` if not. */
private fun restoreSource(name: String?, hasValue: Boolean): AssignmentSource =
    name?.let { n -> AssignmentSource.entries.firstOrNull { it.name == n } }
        ?: if (hasValue) AssignmentSource.USER else AssignmentSource.DEFAULT

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
    sortOrder = sortOrder,
    flow = flow.name,
)

internal fun BackupCategory.toEntity() = CategoryEntity(
    id = id,
    name = name,
    icon = icon,
    colorHex = colorHex,
    isCustom = isCustom,
    monthlyCap = monthlyCap?.let(::BigDecimal),
    parentCategoryId = parentCategoryId,
    sortOrder = sortOrder,
    flow = com.xemmy.mmbank.domain.model.CategoryFlow.fromStored(flow),
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

internal fun AccountChangeEntity.toBackup() = BackupAccountChange(
    id = id,
    accountId = accountId,
    accountName = accountName,
    timestamp = timestamp,
    type = type.name,
    source = source.name,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    balanceDelta = balanceDelta?.toPlainString(),
    currencyCode = currencyCode,
    transactionId = transactionId,
    reason = reason,
    note = note,
)

internal fun BackupAccountChange.toEntity() = AccountChangeEntity(
    id = id,
    accountId = accountId,
    accountName = accountName,
    timestamp = timestamp,
    type = AccountChangeType.parse(type),
    source = AccountChangeSource.parse(source),
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    balanceDelta = balanceDelta?.toBigDecimalOrNull(),
    currencyCode = currencyCode,
    transactionId = transactionId,
    reason = reason,
    note = note,
    isDemo = false,
)

internal fun IgnoredKeywordEntity.toBackup() = BackupIgnoredKeyword(
    id = id,
    keyword = keyword,
    createdAt = createdAt,
)

internal fun BackupIgnoredKeyword.toEntity() = IgnoredKeywordEntity(
    id = id,
    keyword = keyword,
    createdAt = createdAt,
)

// --- format 11 mappers ---

internal fun BillEntity.toBackup() = BackupBill(
    id = id, name = name, amount = amount.toPlainString(), currencyCode = currencyCode,
    recurrence = recurrence?.name, nextDueDateMillis = nextDueDateMillis, createdAt = createdAt,
    dueDayAnchor = dueDayAnchor, providerKey = providerKey, accountRef = accountRef, lastPaidMillis = lastPaidMillis,
)

internal fun BackupBill.toEntity() = BillEntity(
    id = id, name = name, amount = BigDecimal(amount), currencyCode = currencyCode,
    recurrence = recurrence?.let { r -> BillRecurrence.values().firstOrNull { it.name == r } },
    nextDueDateMillis = nextDueDateMillis, createdAt = createdAt, dueDayAnchor = dueDayAnchor,
    providerKey = providerKey, accountRef = accountRef, lastPaidMillis = lastPaidMillis,
)

internal fun SubscriptionEntity.toBackup() = BackupManualSubscription(
    id = id, merchant = merchant, averageAmount = averageAmount.toPlainString(), currencyCode = currencyCode,
    cadence = cadence.name, nextDueDateMillis = nextDueDateMillis, lastSeenMillis = lastSeenMillis,
    occurrenceCount = occurrenceCount, dismissed = dismissed, lines = lines,
)

internal fun BackupManualSubscription.toEntity() = SubscriptionEntity(
    id = id, merchant = merchant, averageAmount = BigDecimal(averageAmount), currencyCode = currencyCode,
    cadence = SubscriptionCadence.entries.firstOrNull { it.name == cadence } ?: SubscriptionCadence.MONTHLY,
    nextDueDateMillis = nextDueDateMillis, lastSeenMillis = lastSeenMillis, occurrenceCount = occurrenceCount,
    dismissed = dismissed, previousAmount = null, isManual = true,
    merchantKey = SubscriptionRescan.keyOf(merchant), lines = lines,
)

internal fun PayLaterPlanEntity.toBackup() = BackupPayLaterPlan(
    id = id, merchant = merchant, provider = provider, totalAmount = totalAmount.toPlainString(),
    currencyCode = currencyCode, installmentCount = installmentCount, cadence = cadence.name,
    payFromAccountId = payFromAccountId, payToAccountId = payToAccountId,
    sourceTransactionId = sourceTransactionId, categoryId = categoryId, note = note,
    createdAt = createdAt, isDemo = isDemo, sourceIsInstallment = sourceIsInstallment,
)

internal fun BackupPayLaterPlan.toEntity() = PayLaterPlanEntity(
    id = id, merchant = merchant, provider = provider, totalAmount = BigDecimal(totalAmount),
    currencyCode = currencyCode, installmentCount = installmentCount,
    cadence = PayLaterCadence.entries.firstOrNull { it.name == cadence } ?: PayLaterCadence.MONTHLY,
    payFromAccountId = payFromAccountId, payToAccountId = payToAccountId,
    sourceTransactionId = sourceTransactionId, categoryId = categoryId, note = note,
    createdAt = createdAt, isDemo = isDemo, sourceIsInstallment = sourceIsInstallment,
)

internal fun PayLaterInstallmentEntity.toBackup() = BackupPayLaterInstallment(
    id = id, planId = planId, number = number, amount = amount.toPlainString(),
    dueDateMillis = dueDateMillis, paidAtMillis = paidAtMillis, paymentTransactionId = paymentTransactionId,
)

internal fun BackupPayLaterInstallment.toEntity() = PayLaterInstallmentEntity(
    id = id, planId = planId, number = number, amount = BigDecimal(amount),
    dueDateMillis = dueDateMillis, paidAtMillis = paidAtMillis, paymentTransactionId = paymentTransactionId,
)

internal fun SavingsGoalEntity.toBackup() = BackupGoal(
    id = id, title = title, targetAmount = targetAmount.toPlainString(),
    currentAmount = currentAmount.toPlainString(), targetDateMillis = targetDateMillis,
    icon = icon, createdAt = createdAt, archived = archived,
)

internal fun BackupGoal.toEntity() = SavingsGoalEntity(
    id = id, title = title, targetAmount = BigDecimal(targetAmount), currentAmount = BigDecimal(currentAmount),
    targetDateMillis = targetDateMillis, icon = icon, createdAt = createdAt, archived = archived,
)

internal fun FriendEntity.toBackup() = BackupFriend(id = id, name = name, createdAt = createdAt, archived = archived)

internal fun BackupFriend.toEntity() = FriendEntity(id = id, name = name, createdAt = createdAt, archived = archived)

internal fun SplitGroupEntity.toBackup() =
    BackupGroup(id = id, name = name, icon = icon, createdAt = createdAt, archived = archived)

internal fun BackupGroup.toEntity() =
    SplitGroupEntity(id = id, name = name, icon = icon, createdAt = createdAt, archived = archived)

internal fun GroupExpenseEntity.toBackup() = BackupGroupExpense(
    id = id, groupId = groupId, description = description, amount = amount.toPlainString(),
    currencyCode = currencyCode, paidByFriendId = paidByFriendId, splitType = splitType.name, createdAt = createdAt,
)

internal fun BackupGroupExpense.toEntity() = GroupExpenseEntity(
    id = id, groupId = groupId, description = description, amount = BigDecimal(amount),
    currencyCode = currencyCode, paidByFriendId = paidByFriendId,
    splitType = SplitType.values().firstOrNull { it.name == splitType } ?: SplitType.EQUAL, createdAt = createdAt,
)

internal fun ExpenseSplitEntity.toBackup() =
    BackupExpenseSplit(id = id, expenseId = expenseId, friendId = friendId, owedAmount = owedAmount.toPlainString())

internal fun BackupExpenseSplit.toEntity() =
    ExpenseSplitEntity(id = id, expenseId = expenseId, friendId = friendId, owedAmount = BigDecimal(owedAmount))
