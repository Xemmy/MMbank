package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountChangeDao
import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.BillDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateHistoryDao
import com.xemmy.mmbank.data.local.dao.FriendDao
import com.xemmy.mmbank.data.local.dao.GroupExpenseDao
import com.xemmy.mmbank.data.local.dao.IgnoredKeywordDao
import com.xemmy.mmbank.data.local.dao.IgnoredMessagePatternDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.dao.SavingsGoalDao
import com.xemmy.mmbank.data.local.dao.SenderLabelDao
import com.xemmy.mmbank.data.local.dao.SplitGroupDao
import com.xemmy.mmbank.data.local.dao.SubscriptionDao
import androidx.room.withTransaction
import com.xemmy.mmbank.domain.repository.PayLaterRepository.PayResult
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.IgnoredKeywordEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupMemberEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.data.remote.forex.ForexApi
import com.xemmy.mmbank.domain.account.AccountChangeDiff
import com.xemmy.mmbank.domain.intelligence.BillAdvancer
import com.xemmy.mmbank.domain.intelligence.SubscriptionRescan
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.IgnoredKeyword
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.MessageSimilarity
import com.xemmy.mmbank.domain.repository.AccountRepository
import com.xemmy.mmbank.domain.repository.BillRepository
import com.xemmy.mmbank.domain.repository.FriendRepository
import com.xemmy.mmbank.domain.repository.IgnoredKeywordRepository
import com.xemmy.mmbank.domain.repository.IgnoredMessageRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.RatesRepository
import com.xemmy.mmbank.domain.repository.SavingsGoalRepository
import com.xemmy.mmbank.domain.repository.SenderLabelRepository
import com.xemmy.mmbank.domain.repository.SplitGroupRepository
import com.xemmy.mmbank.domain.repository.RescanCounts
import com.xemmy.mmbank.domain.repository.SubscriptionRepository
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class AccountRepositoryImpl(
    private val dao: AccountDao,
    private val parsingRuleDao: ParsingRuleDao,
    /** Whether "Demo mode" is on; see TransactionRepositoryImpl. */
    private val demoMode: StateFlow<Boolean>,
    /**
     * Where the account change log is written (see [AccountChange]). Null
     * disables logging, which keeps the hand-written test fakes working as before.
     */
    private val changes: AccountChangeDao? = null,
    /** When given, each change and its log entry commit in one Room transaction. */
    private val database: AppDatabase? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountRepository {

    private suspend fun <T> atomically(block: suspend () -> T): T =
        if (database != null) database.withTransaction { block() } else block()

    private suspend fun log(vararg entries: AccountChange) {
        if (changes != null && entries.isNotEmpty()) changes.insertAll(entries.map { it.toEntity() })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActive(): Flow<List<Account>> =
        demoMode.flatMapLatest { demo -> dao.observeActive(demo) }
            .map { list -> list.map(AccountEntity::toDomain) }

    override fun observeActiveReal(): Flow<List<Account>> =
        dao.observeActive(false).map { list -> list.map(AccountEntity::toDomain) }

    override suspend fun setOrder(orderedIds: List<Long>) = atomically {
        orderedIds.forEachIndexed { index, id -> dao.setSortOrder(id, index) }
    }

    override suspend fun save(account: Account): Long = atomically {
        if (account.id == 0L) {
            // A new account goes to the end of the list unless the caller chose a position.
            val order = if (account.sortOrder == 0) dao.maxSortOrder() + 1 else account.sortOrder
            val id = dao.insert(account.copy(sortOrder = order).toEntity())
            log(AccountChangeDiff.created(account.copy(id = id), clock()))
            id
        } else {
            val old = dao.getById(account.id)?.toDomain()
            dao.update(account.toEntity())
            if (old != null) log(*AccountChangeDiff.diff(old, account, clock()).toTypedArray())
            account.id
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeArchived(): Flow<List<Account>> =
        demoMode.flatMapLatest { demo -> dao.observeArchived(demo) }
            .map { list -> list.map(AccountEntity::toDomain) }

    override suspend fun archive(account: Account) = atomically {
        dao.update(account.copy(archived = true).toEntity())
        log(AccountChangeDiff.archived(account, clock()))
    }

    override suspend fun unarchive(account: Account) = atomically {
        dao.update(account.copy(archived = false).toEntity())
        log(AccountChangeDiff.restored(account, clock()))
    }

    override suspend fun delete(account: Account) = atomically {
        // Logged first, as a snapshot: the account row (and the link on its transactions) is about to go.
        log(AccountChangeDiff.deleted(account, clock()))
        parsingRuleDao.clearAssignAccountId(account.id)
        dao.delete(account.toEntity())
    }

    override fun observeChanges(accountId: Long): Flow<List<AccountChange>> =
        changes?.observeForAccount(accountId)?.map { rows -> rows.map { it.toDomain() } } ?: flowOf(emptyList())
}

class ParsingRuleRepositoryImpl(
    private val dao: ParsingRuleDao,
    /** Whether "Demo mode" is on; see TransactionRepositoryImpl. */
    private val demoMode: StateFlow<Boolean>,
) : ParsingRuleRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<ParsingRule>> =
        demoMode.flatMapLatest { demo -> dao.observeAll(demo) }
            .map { list -> list.map(ParsingRuleEntity::toDomain) }

    override fun observeAllReal(): Flow<List<ParsingRule>> =
        dao.observeAll(false).map { list -> list.map(ParsingRuleEntity::toDomain) }

    override suspend fun getEnabled(): List<ParsingRule> =
        dao.getEnabled().map(ParsingRuleEntity::toDomain)

    override suspend fun getById(id: Long): ParsingRule? = dao.getById(id)?.toDomain()

    override suspend fun save(rule: ParsingRule): Long = dao.upsert(rule.toEntity())

    override suspend fun delete(id: Long) = dao.deleteById(id)
}

class SenderLabelRepositoryImpl(private val dao: SenderLabelDao) : SenderLabelRepository {

    override fun observeLabels(): Flow<Map<String, String>> =
        dao.observeAll().map { list -> list.associate { it.sender to it.label } }

    override suspend fun setLabel(sender: String, label: String) =
        dao.upsert(SenderLabelEntity(sender = sender, label = label))

    override suspend fun clearLabel(sender: String) = dao.delete(sender)
}

class IgnoredMessageRepositoryImpl(
    private val dao: IgnoredMessagePatternDao,
) : IgnoredMessageRepository {

    override fun observeAll(): Flow<List<IgnoredMessagePattern>> =
        dao.observeAll().map { list -> list.map(IgnoredMessagePatternEntity::toDomain) }

    override suspend fun add(sample: String, now: Long): Boolean {
        val trimmed = sample.trim()
        val fingerprint = MessageSimilarity.fingerprint(trimmed)
        // No fingerprint means nothing to match on later, so there is nothing
        // worth storing -- see IgnoredMessageRepository.add.
        if (fingerprint.isEmpty()) return false
        dao.upsert(
            IgnoredMessagePatternEntity(fingerprint = fingerprint, sample = trimmed, createdAt = now),
        )
        return true
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)
}

class IgnoredKeywordRepositoryImpl(
    private val dao: IgnoredKeywordDao,
) : IgnoredKeywordRepository {

    override fun observeAll(): Flow<List<IgnoredKeyword>> =
        dao.observeAll().map { list -> list.map(IgnoredKeywordEntity::toDomain) }

    override suspend fun add(keyword: String, now: Long): Boolean {
        val normalized = keyword.trim().lowercase()
        if (normalized.length < IgnoredKeywordRepository.MIN_LENGTH) return false
        dao.upsert(IgnoredKeywordEntity(keyword = normalized, createdAt = now))
        return true
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)
}

class RatesRepositoryImpl(
    private val api: ForexApi,
    private val dao: ExchangeRateDao,
    private val historyDao: ExchangeRateHistoryDao,
    private val today: () -> java.time.LocalDate = { java.time.LocalDate.now() },
) : RatesRepository {

    override fun observeRates(): Flow<List<ExchangeRate>> =
        dao.observeAll().map { list -> list.map(ExchangeRateEntity::toDomain) }

    override fun observeHistory(baseCurrency: String): Flow<List<com.xemmy.mmbank.domain.model.ExchangeRateOnDay>> =
        historyDao.observeForBase(baseCurrency.trim().uppercase())
            .map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(baseCurrency: String): Result<Int> = runCatching {
        val response = api.latest(baseCurrency.trim().uppercase())
        if (response.result != "success") {
            error("Rate provider error: ${response.errorType ?: "unknown"} (check the currency code)")
        }
        val now = System.currentTimeMillis()
        val entities = response.rates
            .filterKeys { !it.equals(response.baseCode, ignoreCase = true) }
            .map { (quote, rate) ->
                ExchangeRateEntity(
                    baseCode = response.baseCode.uppercase(),
                    quoteCode = quote.uppercase(),
                    rate = BigDecimal.valueOf(rate),
                    fetchedAt = now,
                )
            }
        dao.upsertAll(entities)
        // Keep today's rates for the currencies in use, so a later look at this
        // day uses this day's rate (see RateHistory). A failure here must not
        // fail the refresh itself: the latest rates are already stored.
        runCatching {
            historyDao.upsertAll(
                com.xemmy.mmbank.domain.account.RateHistory.snapshot(
                    base = response.baseCode,
                    ratesFromBase = entities.associate { it.quoteCode to it.rate },
                    heldCodes = historyDao.heldCurrencyCodes(),
                    day = today(),
                ).map { it.toEntity() },
            )
        }
        entities.size
    }
}

class SubscriptionRepositoryImpl(
    private val dao: SubscriptionDao,
    private val neverKeys: () -> Set<String> = { emptySet() },
) : SubscriptionRepository {

    /**
     * Every write to the subscription tables goes through this lock. A rescan reads the table,
     * plans, then writes; two rescans at once (one per captured SMS of a pasted batch, or the
     * screen's scan during a capture) each inserted the same new detection, and a rescan that
     * read the table before the user tapped Track wrote the row back as detected.
     */
    private val writeLock = kotlinx.coroutines.sync.Mutex()

    override fun observeActive(): Flow<List<Subscription>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observePossible(): Flow<List<Subscription>> =
        dao.observePossible().map { list -> list.map { it.toDomain() } }

    override suspend fun track(id: Long): Unit = writeLock.withLock {
        val row = dao.getAll().firstOrNull { it.id == id } ?: return@withLock
        dao.upsert(row.copy(isManual = true, confidence = 1.0, dismissed = false))
        Unit
    }

    override fun observeDismissed(): Flow<List<Subscription>> =
        dao.observeDismissed().map { list -> list.map { it.toDomain() } }

    override suspend fun dismiss(id: Long): Unit = writeLock.withLock { dao.dismiss(id) }

    override suspend fun restore(id: Long): Unit = writeLock.withLock { dao.restore(id) }

    override suspend fun saveManual(subscription: Subscription): Long = writeLock.withLock {
        val row = subscription.copy(
            isManual = true,
            dismissed = false,
            merchantKey = SubscriptionRescan.keyOf(subscription.merchant),
        )
        dao.upsert(row.toEntity())
    }

    override suspend fun delete(id: Long): Unit = writeLock.withLock {
        dao.delete(id)
        dao.deleteLinksFor(id)
    }

    override fun observeLinks(): Flow<List<SubscriptionLink>> =
        dao.observeLinks().map { list -> list.map { it.toDomain() } }

    override suspend fun links(): List<SubscriptionLink> = dao.getAllLinks().map { it.toDomain() }

    override suspend fun link(transactionId: Long, subscriptionId: Long): Unit = writeLock.withLock {
        dao.upsertLink(SubscriptionLink(transactionId, subscriptionId).toEntity())
        // Linking a charge to a detected row makes it the user's: a rescan must not delete or re-detect it.
        val row = dao.getAll().firstOrNull { it.id == subscriptionId }
        if (row != null && !row.isManual) dao.upsert(row.copy(isManual = true))
        Unit
    }

    override suspend fun unlink(transactionId: Long): Unit = writeLock.withLock { dao.deleteLink(transactionId) }

    override suspend fun rescan(transactions: List<Transaction>): RescanCounts = writeLock.withLock {
        val plan = SubscriptionRescan.plan(
            stored = dao.getAll().map { it.toDomain() },
            links = links(),
            ledger = transactions,
            nowMillis = System.currentTimeMillis(),
            never = neverKeys(),
        )
        plan.newLinks.forEach { dao.upsertLink(it.toEntity()) }
        plan.upserts.forEach { dao.upsert(it.toEntity()) }
        if (plan.deleteIds.isNotEmpty()) {
            dao.deleteAll(plan.deleteIds)
            plan.deleteIds.forEach { dao.deleteLinksFor(it) }
        }
        RescanCounts(plan.created, plan.updated, plan.removed)
    }

    override suspend fun linkedTransactions(subscriptionId: Long): List<Transaction> =
        dao.linkedTransactions(subscriptionId).map { it.toDomain() }

    override suspend fun restoreRow(snapshot: com.xemmy.mmbank.domain.intelligence.SubscriptionSnapshot): Unit = writeLock.withLock {
        dao.upsert(snapshot.row.toEntity())
        snapshot.links.forEach { dao.upsertLink(it.toEntity()) }
    }
}

class MerchantAliasRepositoryImpl(
    private val database: com.xemmy.mmbank.data.local.AppDatabase,
    private val transactionDao: com.xemmy.mmbank.data.local.dao.TransactionDao,
    private val dao: com.xemmy.mmbank.data.local.dao.MerchantAliasDao,
    /** Re-evaluates subscriptions after a rename (wired to the subscription rescan by AppContainer). */
    private val afterRename: suspend () -> Unit = {},
) : com.xemmy.mmbank.domain.repository.MerchantAliasRepository {

    override fun observeAll(): Flow<List<com.xemmy.mmbank.domain.model.MerchantAlias>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun countMerchant(name: String): Int = transactionDao.countByMerchant(name)

    override suspend fun renameMerchant(from: String, to: String, alsoFuture: Boolean): Int {
        val target = to.trim()
        if (target.isEmpty()) return 0
        val count = database.withTransaction {
            val renamed = transactionDao.renameMerchant(from, target)
            val key = com.xemmy.mmbank.domain.intelligence.MerchantAliases.keyOf(from)
            if (alsoFuture && key.isNotEmpty() && key != com.xemmy.mmbank.domain.intelligence.MerchantAliases.keyOf(target)) {
                dao.upsert(com.xemmy.mmbank.data.local.entity.MerchantAliasEntity(key, target))
            }
            renamed
        }
        afterRename()
        return count
    }

    override suspend fun delete(fromKey: String) = dao.delete(fromKey)
}

class BillRepositoryImpl(private val dao: BillDao) : BillRepository {

    override fun observeAll(): Flow<List<Bill>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun save(bill: Bill): Long =
        if (bill.id == 0L) {
            dao.insert(bill.toEntity())
        } else {
            dao.update(bill.toEntity())
            bill.id
        }

    /**
     * [BillAdvancer] decides the outcome: a recurring bill's row is updated
     * with the advanced due date, a one-off bill's row is deleted instead
     * (see [BillRepository.markPaid]).
     */
    override suspend fun markPaid(billId: Long) {
        val current = dao.getById(billId)?.toDomain() ?: return
        val paid = BillAdvancer.markPaid(current, System.currentTimeMillis())
        if (paid != null) {
            dao.markPaid(billId, paid.nextDueDateMillis, paid.lastPaidMillis ?: System.currentTimeMillis())
        } else {
            dao.delete(billId)
        }
    }

    override suspend fun delete(billId: Long) = dao.delete(billId)
}

class SavingsGoalRepositoryImpl(private val dao: SavingsGoalDao) : SavingsGoalRepository {

    override fun observeActive(): Flow<List<SavingsGoal>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    override suspend fun save(goal: SavingsGoal): Long =
        if (goal.id == 0L) {
            dao.insert(goal.toEntity())
        } else {
            dao.update(goal.toEntity())
            goal.id
        }

    override suspend fun allocate(goalId: Long, amount: BigDecimal) {
        val current = dao.getById(goalId) ?: return
        dao.setCurrentAmount(goalId, current.currentAmount + amount)
    }

    override suspend fun archive(goalId: Long) = dao.archive(goalId)

    override suspend fun delete(goalId: Long) = dao.delete(goalId)
}

class FriendRepositoryImpl(private val dao: FriendDao) : FriendRepository {

    override fun observeActive(): Flow<List<Friend>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    override suspend fun save(friend: Friend): Long =
        if (friend.id == 0L) {
            dao.insert(friend.toEntity())
        } else {
            dao.update(friend.toEntity())
            friend.id
        }

    override suspend fun archive(friendId: Long) = dao.archive(friendId)
}

class SplitGroupRepositoryImpl(
    private val groupDao: SplitGroupDao,
    private val expenseDao: GroupExpenseDao,
) : SplitGroupRepository {

    override fun observeActive(): Flow<List<SplitGroup>> =
        combine(groupDao.observeActive(), groupDao.observeAllMemberships()) { groups, memberships ->
            val membersByGroup = memberships.groupBy { it.groupId }
            groups.map { group -> group.toDomain(membersByGroup[group.id].orEmpty().map { it.friendId }) }
        }

    /** Member list is replaced wholesale on every save -- simpler than diffing adds/removes for a handful of friends. */
    override suspend fun save(group: SplitGroup): Long {
        val id = if (group.id == 0L) {
            groupDao.insert(group.toEntity())
        } else {
            groupDao.update(group.toEntity())
            group.id
        }
        groupDao.clearMembers(id)
        if (group.memberFriendIds.isNotEmpty()) {
            groupDao.insertMembers(group.memberFriendIds.map { SplitGroupMemberEntity(id, it) })
        }
        return id
    }

    override suspend fun archive(groupId: Long) = groupDao.archive(groupId)

    override fun observeAllExpenses(): Flow<List<GroupExpense>> =
        expenseDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeAllSplits(): Flow<List<ExpenseSplit>> =
        expenseDao.observeAllSplits().map { list -> list.map { it.toDomain() } }

    override suspend fun saveExpense(expense: GroupExpense, splits: List<ExpenseSplit>): Long {
        val expenseId = if (expense.id == 0L) {
            expenseDao.insert(expense.toEntity())
        } else {
            expenseDao.update(expense.toEntity())
            expenseDao.deleteSplitsForExpense(expense.id)
            expense.id
        }
        expenseDao.insertSplits(splits.map { it.copy(expenseId = expenseId).toEntity() })
        return expenseId
    }

    override suspend fun deleteExpense(expenseId: Long) = expenseDao.delete(expenseId)
}

class PayLaterRepositoryImpl(
    private val database: com.xemmy.mmbank.data.local.AppDatabase,
    private val dao: com.xemmy.mmbank.data.local.dao.PayLaterDao,
    private val transactionDao: com.xemmy.mmbank.data.local.dao.TransactionDao,
    private val accountDao: com.xemmy.mmbank.data.local.dao.AccountDao,
    private val transactions: com.xemmy.mmbank.domain.repository.TransactionRepository,
) : com.xemmy.mmbank.domain.repository.PayLaterRepository {

    override fun observePlans(): Flow<List<com.xemmy.mmbank.domain.model.PayLaterPlan>> =
        dao.observePlans().map { list -> list.map { it.toDomain() } }

    override fun observeInstallments(): Flow<List<com.xemmy.mmbank.domain.model.PayLaterInstallment>> =
        dao.observeInstallments().map { list -> list.map { it.toDomain() } }

    override suspend fun getPlan(planId: Long) = dao.getPlan(planId)?.toDomain()

    override suspend fun getInstallments(planId: Long) = dao.getInstallments(planId).map { it.toDomain() }

    override suspend fun createPlan(
        plan: com.xemmy.mmbank.domain.model.PayLaterPlan,
        installments: List<com.xemmy.mmbank.domain.model.PayLaterInstallment>,
    ): Long = dao.insertPlanWithInstallments(plan.copy(id = 0).toEntity(), installments.map { it.copy(id = 0).toEntity() })

    override suspend fun updatePlan(plan: com.xemmy.mmbank.domain.model.PayLaterPlan) = dao.updatePlan(plan.toEntity())

    override suspend fun deletePlan(planId: Long) = dao.deletePlan(planId)

    override suspend fun reschedule(installmentId: Long, dueDateMillis: Long) {
        val row = dao.getInstallment(installmentId) ?: return
        dao.updateInstallment(row.copy(dueDateMillis = dueDateMillis))
    }

    override suspend fun pay(
        installmentId: Long,
        fromAccountId: Long,
        paidAtMillis: Long,
    ): PayResult {
        val installment = dao.getInstallment(installmentId)?.toDomain() ?: return PayResult.NOT_FOUND
        if (installment.isPaid) return PayResult.ALREADY_PAID
        val plan = dao.getPlan(installment.planId)?.toDomain() ?: return PayResult.NOT_FOUND
        suspend fun endpoint(id: Long?) = id?.let { accountDao.getById(it) }?.let {
            com.xemmy.mmbank.domain.ledger.TransferLegs.Endpoint(it.id, it.name, it.type, it.currencyCode)
        }
        val from = endpoint(fromAccountId) ?: return PayResult.NO_ACCOUNT
        val to = endpoint(plan.payToAccountId)?.takeIf { it.accountId != from.accountId }
        val legs = com.xemmy.mmbank.domain.paylater.PayLaterPayment.build(plan, installment, from, to, paidAtMillis)
        database.withTransaction {
            val payingId = transactionDao.insert(legs.paying.copy(isDemo = plan.isDemo).toEntity())
            if (legs.counterpart != null) {
                val otherId = transactionDao.insert(legs.counterpart.copy(isDemo = plan.isDemo).toEntity())
                val kind = legs.paying.kind
                val category = com.xemmy.mmbank.domain.model.SpendingCategory.TRANSFERS
                transactionDao.linkAsTransfer(payingId, otherId, kind, category)
                transactionDao.linkAsTransfer(otherId, payingId, kind, category)
            }
            dao.updateInstallment(
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.markPaid(installment, payingId, paidAtMillis).toEntity(),
            )
        }
        return PayResult.PAID
    }

    private suspend fun endpointOf(accountId: Long?): com.xemmy.mmbank.domain.ledger.TransferLegs.Endpoint? =
        accountId?.let { accountDao.getById(it) }?.let {
            com.xemmy.mmbank.domain.ledger.TransferLegs.Endpoint(it.id, it.name, it.type, it.currencyCode)
        }

    /**
     * The captured SMS takes the plan's payment shape ([PayLaterPayment.matchedShape]): for a plan
     * whose full price was counted at purchase, the installment SMS is hidden from spending, or
     * becomes a transfer to the provider's account when one is tracked. It used to stay an ordinary
     * spend, so a full-price purchase of 400 in four installments counted 700.
     */
    override suspend fun matchInstallment(installmentId: Long, transactionId: Long, paidAtMillis: Long) {
        val installment = dao.getInstallment(installmentId)?.toDomain() ?: return
        if (installment.isPaid) return
        val plan = dao.getPlan(installment.planId)?.toDomain()
        val matched = transactionDao.getById(transactionId)
        val shape = if (plan != null && matched != null) {
            com.xemmy.mmbank.domain.paylater.PayLaterPayment.matchedShape(plan, matched.accountId)
        } else {
            com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.SPEND
        }
        val from = if (shape == com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.TRANSFER_PAIR) endpointOf(matched?.accountId) else null
        val to = if (from != null) endpointOf(plan?.payToAccountId)?.takeIf { it.accountId != from.accountId } else null
        database.withTransaction {
            when (shape) {
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.SPEND -> Unit
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.EXCLUDED_DEBIT ->
                    transactionDao.bulkSetExcludeFromStats(listOf(transactionId), true)
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.TRANSFER_PAIR -> {
                    val counterpart = if (plan != null && from != null && to != null) {
                        com.xemmy.mmbank.domain.paylater.PayLaterPayment.build(plan, installment, from, to, paidAtMillis)
                    } else {
                        null
                    }
                    val leg = counterpart?.counterpart
                    if (counterpart != null && leg != null) {
                        val otherId = transactionDao.insert(leg.copy(isDemo = plan?.isDemo ?: false).toEntity())
                        val kind = counterpart.paying.kind
                        val category = com.xemmy.mmbank.domain.model.SpendingCategory.TRANSFERS
                        transactionDao.linkAsTransfer(transactionId, otherId, kind, category)
                        transactionDao.linkAsTransfer(otherId, transactionId, kind, category)
                    } else {
                        transactionDao.bulkSetExcludeFromStats(listOf(transactionId), true)
                    }
                }
            }
            dao.updateInstallment(
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.markPaid(installment, transactionId, paidAtMillis).toEntity(),
            )
        }
    }

    override suspend fun undoPayment(installmentId: Long) {
        val installment = dao.getInstallment(installmentId)?.toDomain() ?: return
        if (!installment.isPaid) return
        val payingId = installment.paymentTransactionId
        val paying = payingId?.let { transactionDao.getById(it) }
        val label = com.xemmy.mmbank.domain.paylater.PayLaterPayment.LABEL
        // Only transactions this feature recorded are removed; a matched SMS or the split's own charge stays.
        if (paying != null && paying.matchedRuleName == label) {
            transactions.deleteMany(listOfNotNull(paying.id, paying.linkedTransactionId))
        } else if (paying != null) {
            // An auto-matched SMS: undo what matching did to it, and keep the SMS itself.
            val partner = paying.linkedTransactionId?.let { transactionDao.getById(it) }
            if (partner != null && partner.matchedRuleName == label) transactions.deleteMany(listOf(partner.id))
            val plan = dao.getPlan(installment.planId)?.toDomain()
            if (plan != null && paying.excludeFromStats &&
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.matchedShape(plan, paying.accountId) !=
                com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape.SPEND
            ) {
                transactionDao.bulkSetExcludeFromStats(listOf(paying.id), false)
            }
        }
        dao.updateInstallment(com.xemmy.mmbank.domain.paylater.PayLaterPayment.markUnpaid(installment).toEntity())
    }
}
