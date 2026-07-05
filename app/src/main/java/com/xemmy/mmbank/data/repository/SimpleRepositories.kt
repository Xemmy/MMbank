package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.dao.SenderLabelDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.data.remote.forex.ForexApi
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.repository.AccountRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.RatesRepository
import com.xemmy.mmbank.domain.repository.SenderLabelRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountRepositoryImpl(private val dao: AccountDao) : AccountRepository {

    override fun observeActive(): Flow<List<Account>> =
        dao.observeActive().map { list -> list.map(AccountEntity::toDomain) }

    override suspend fun save(account: Account): Long =
        if (account.id == 0L) {
            dao.insert(account.toEntity())
        } else {
            dao.update(account.toEntity())
            account.id
        }

    override suspend fun archive(account: Account) = dao.update(account.copy(archived = true).toEntity())

    override suspend fun delete(account: Account) = dao.delete(account.toEntity())
}

class ParsingRuleRepositoryImpl(private val dao: ParsingRuleDao) : ParsingRuleRepository {

    override fun observeAll(): Flow<List<ParsingRule>> =
        dao.observeAll().map { list -> list.map(ParsingRuleEntity::toDomain) }

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

class RatesRepositoryImpl(
    private val api: ForexApi,
    private val dao: ExchangeRateDao,
) : RatesRepository {

    override fun observeRates(): Flow<List<ExchangeRate>> =
        dao.observeAll().map { list -> list.map(ExchangeRateEntity::toDomain) }

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
        entities.size
    }
}
