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
import com.xemmy.mmbank.domain.intelligence.SubscriptionMerge
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
import com.xemmy.mmbank.domain.repository.SubscriptionRepository
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

    override suspend fun save(account: Account): Long = atomically {
        if (account.id == 0L) {
            val id = dao.insert(account.toEntity())
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

class SubscriptionRepositoryImpl(private val dao: SubscriptionDao) : SubscriptionRepository {

    override fun observeActive(): Flow<List<Subscription>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    /**
     * A fresh scan's results replace the stored set one merchant at a time
     * (REPLACE on the unique merchant index -- see [SubscriptionDao.upsert]),
     * preserving a merchant's [Subscription.dismissed] flag if it was already
     * dismissed and is detected again, so a re-scan can't silently un-dismiss
     * something the user already dealt with.
     */
    override suspend fun replaceAll(detected: List<Subscription>) {
        // Reuses each stored row's id (REPLACE would mint a new one and re-arm
        // every renewal reminder) and its dismissed flag.
        val stored = dao.getAll().map { it.toDomain() }
        SubscriptionMerge.merge(stored, detected).forEach { dao.upsert(it.toEntity()) }
    }

    override suspend fun dismiss(id: Long) = dao.dismiss(id)
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
        val advanced = BillAdvancer.advance(current)
        if (advanced != null) {
            dao.updateNextDueDate(billId, advanced.nextDueDateMillis)
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
