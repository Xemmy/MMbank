package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestCapturedMessageUseCaseTest {

    private class FakeRuleRepository(
        val rules: MutableStateFlow<List<ParsingRule>>,
    ) : ParsingRuleRepository {
        override fun observeAll(): Flow<List<ParsingRule>> = rules
        override suspend fun getEnabled(): List<ParsingRule> = rules.value.filter { it.enabled }
        override suspend fun getById(id: Long): ParsingRule? = rules.value.firstOrNull { it.id == id }
        override suspend fun save(rule: ParsingRule): Long = rule.id
        override suspend fun delete(id: Long) = Unit
    }

    private class RecordingTransactionRepository : TransactionRepository {
        var lastParsed: ParsedTransaction? = null
        override suspend fun recordParsed(parsed: ParsedTransaction, message: CapturedMessage): IngestResult {
            lastParsed = parsed
            return IngestResult.Inserted(1L)
        }

        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeNetByAccount(): Flow<Map<Long, Double>> = flowOf(emptyMap())
        override suspend fun recordManual(transaction: Transaction): Long = 1L
        override suspend fun update(transaction: Transaction) = Unit
        override suspend fun assignAccount(transactionId: Long, accountId: Long?) = Unit
        override suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int = 0
        override suspend fun setPerson(transactionId: Long, person: String?) = Unit
        override suspend fun setSpendingCategory(
            transactionId: Long,
            category: com.xemmy.mmbank.domain.model.SpendingCategory,
        ) = Unit
        override suspend fun renamePerson(from: String, to: String?) = Unit
        override suspend fun delete(transactionId: Long) = Unit
        override suspend fun bulkSetCategory(
            ids: List<Long>,
            category: com.xemmy.mmbank.domain.model.SpendingCategory,
        ): Int = 0
        override suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int = 0
        override suspend fun deleteMany(ids: List<Long>): Int = 0
    }

    private val recorder = RecordingTransactionRepository()
    private val ruleFlow = MutableStateFlow<List<ParsingRule>>(emptyList())

    private fun useCase(): IngestCapturedMessageUseCase {
        // Unconfined scope: the cache's stateIn collects the fake flow synchronously.
        val cache = CompiledRuleCache(
            FakeRuleRepository(ruleFlow),
            CoroutineScope(Dispatchers.Unconfined),
        )
        return IngestCapturedMessageUseCase(cache, recorder) { "USD" }
    }

    private fun push(sender: String, body: String, title: String? = null) = CapturedMessage(
        source = TransactionSource.PUSH_NOTIFICATION,
        sender = sender,
        title = title,
        body = body,
        timestampMillis = 0L,
    )

    private val bankStyleBody = "Payment of AED 250.00 at CARREFOUR was made from your account *617"

    @Test
    fun `bank app notification still parses via built-ins`() = runBlocking {
        val result = useCase().invoke(push("com.adcb.bank", bankStyleBody))
        assertTrue(result is IngestResult.Inserted)
        assertEquals("AED", recorder.lastParsed?.currencyCode)
    }

    @Test
    fun `telegram notification with transaction-looking numbers is ignored`() = runBlocking {
        val result = useCase().invoke(push("org.telegram.messenger", bankStyleBody))
        assertEquals(IngestResult.NoMatch, result)
        assertNull(recorder.lastParsed)
    }

    @Test
    fun `whatsapp notification is ignored too`() = runBlocking {
        val result = useCase().invoke(push("com.whatsapp", "You received 3,000 USD, paid to your wallet"))
        assertEquals(IngestResult.NoMatch, result)
    }

    @Test
    fun `custom rule explicitly targeting telegram still captures`() = runBlocking {
        ruleFlow.value = listOf(
            ParsingRule(
                id = 1,
                name = "Telegram wallet bot",
                pattern = "Wallet: (?<amount>\\d+(?:\\.\\d+)?) (?<currency>[A-Z]{3})",
                senderPattern = "org\\.telegram",
                direction = TransactionDirection.CREDIT,
            )
        )
        val result = useCase().invoke(push("org.telegram.messenger", "Wallet: 42.50 USD received"))
        assertTrue(result is IngestResult.Inserted)
        assertEquals("Telegram wallet bot", recorder.lastParsed?.matchedRuleName)
    }

    @Test
    fun `digit-free message short-circuits to NoMatch`() = runBlocking {
        val result = useCase().invoke(push("com.adcb.bank", "Your statement is ready"))
        assertEquals(IngestResult.NoMatch, result)
    }

    @Test
    fun `sms sender is not affected by the social guard`() = runBlocking {
        val message = CapturedMessage(
            source = TransactionSource.SMS,
            sender = "ADCB",
            title = null,
            body = bankStyleBody,
            timestampMillis = 0L,
        )
        assertTrue(useCase().invoke(message) is IngestResult.Inserted)
    }

    @Test
    fun `tabby decreased adjustment is a credit excluded from stats`() = runBlocking {
        val result = useCase().invoke(
            push(
                sender = "com.tabby.customer",
                body = "Your CAREEM RIDE purchase was decreased by AED 5.58",
                title = "Transaction adjusted",
            )
        )
        assertTrue(result is IngestResult.Inserted)
        val parsed = recorder.lastParsed!!
        assertEquals("builtin.en.adjust.decrease", parsed.matchedRuleName)
        assertEquals(TransactionDirection.CREDIT, parsed.direction)
        assertTrue(parsed.excludeFromStats)
    }

    @Test
    fun `tabby increased adjustment is a debit excluded from stats`() = runBlocking {
        val result = useCase().invoke(
            push(
                sender = "com.tabby.customer",
                body = "Your NOON order was increased by AED 12.00",
                title = "Transaction adjusted",
            )
        )
        assertTrue(result is IngestResult.Inserted)
        val parsed = recorder.lastParsed!!
        assertEquals("builtin.en.adjust.increase", parsed.matchedRuleName)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertTrue(parsed.excludeFromStats)
    }

    @Test
    fun `ordinary purchase is not excluded from stats`() = runBlocking {
        val result = useCase().invoke(push("com.adcb.bank", bankStyleBody))
        assertTrue(result is IngestResult.Inserted)
        assertFalse(recorder.lastParsed!!.excludeFromStats)
    }

    @Test
    fun `rule edits take effect without rebuilding the use case`() = runBlocking {
        val uc = useCase()
        val body = "TXN:99.90/EUR"
        assertEquals(IngestResult.NoMatch, uc.invoke(push("com.adcb.bank", body)))

        ruleFlow.value = listOf(
            ParsingRule(
                id = 2,
                name = "Quirky bank",
                pattern = "TXN:(?<amount>\\d+(?:\\.\\d+)?)/(?<currency>[A-Z]{3})",
                direction = TransactionDirection.DEBIT,
            )
        )
        assertTrue(uc.invoke(push("com.adcb.bank", body)) is IngestResult.Inserted)
        assertEquals("EUR", recorder.lastParsed?.currencyCode)
    }
}
