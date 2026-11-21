package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.parser.IgnoredMessageCache
import com.xemmy.mmbank.domain.parser.MessageSimilarity
import com.xemmy.mmbank.domain.repository.IgnoredMessageRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPastedMessagesUseCaseTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Fixed "now" so the extractor's plausibility window is deterministic. */
    private val now = LocalDateTime.of(2026, 1, 15, 12, 0).atZone(utc).toInstant().toEpochMilli()

    private class FakeRuleRepository : ParsingRuleRepository {
        override fun observeAll(): Flow<List<ParsingRule>> = flowOf(emptyList())
        override fun observeAllReal(): Flow<List<ParsingRule>> = flowOf(emptyList())
        override suspend fun getEnabled(): List<ParsingRule> = emptyList()
        override suspend fun getById(id: Long): ParsingRule? = null
        override suspend fun save(rule: ParsingRule): Long = rule.id
        override suspend fun delete(id: Long) = Unit
    }

    private class FakeIgnoredMessageRepository(private val samples: List<String>) : IgnoredMessageRepository {
        override fun observeAll(): Flow<List<IgnoredMessagePattern>> = flowOf(
            samples.mapIndexed { index, sample ->
                IgnoredMessagePattern(
                    id = index + 1L,
                    fingerprint = MessageSimilarity.fingerprint(sample),
                    sample = sample,
                    createdAt = 0L,
                )
            }
        )
        override suspend fun add(sample: String, now: Long): Boolean = true
        override suspend fun delete(id: Long) = Unit
    }

    /**
     * Records what it was asked to insert, and answers the "already imported"
     * check from that same store — so re-running a paste behaves exactly as it
     * would against a real ledger.
     */
    private class FakeTransactionRepository : TransactionRepository {
        val recorded = mutableListOf<CapturedMessage>()
        val dedupWindows = mutableListOf<Boolean>()
        /** Raw texts the ledger already holds before the paste runs. */
        val preexisting = mutableSetOf<String>()

        override suspend fun recordParsed(
            parsed: ParsedTransaction,
            message: CapturedMessage,
            dedupWindow: Boolean,
        ): IngestResult {
            recorded += message
            dedupWindows += dedupWindow
            return IngestResult.Inserted(recorded.size.toLong())
        }

        override suspend fun findIdByRawMessage(rawMessage: String): Long? =
            if (rawMessage in preexisting || recorded.any { it.body == rawMessage }) 1L else null

        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeLedgerEntries(): Flow<List<AccountBalanceCalculator.LedgerEntry>> = flowOf(emptyList())
        override suspend fun recordManual(transaction: Transaction): Long = 1L
        override suspend fun update(transaction: Transaction) = Unit
        override suspend fun assignAccount(transactionId: Long, accountId: Long?) = Unit
        override suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int = 0
        override suspend fun setPerson(transactionId: Long, person: String?) = Unit
        override suspend fun setSpendingCategory(transactionId: Long, category: SpendingCategory) = Unit
        override suspend fun renamePerson(from: String, to: String?) = Unit
        override suspend fun delete(transactionId: Long) = Unit
        override suspend fun bulkSetCategory(ids: List<Long>, category: SpendingCategory): Int = 0
        override suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int = 0
        override suspend fun deleteMany(ids: List<Long>): Int = 0
        override suspend fun bulkAssignAccount(ids: List<Long>, accountId: Long?): Int = 0
        override suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int = 0
        override suspend fun getAllReal(): List<Transaction> = emptyList()
        override fun observeUncategorized(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun setCategory(transactionId: Long, categoryId: Long?) = Unit
        override suspend fun bulkSetCategoryId(ids: List<Long>, categoryId: Long?): Int = 0
        override suspend fun dismissCategorySuggestion(transactionId: Long) = Unit
        override suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?): Int = 0
    }

    private val repository = FakeTransactionRepository()

    /** Messages the importer handed to Gate 2; must stay empty (see below). */
    private val forwardedToAi = mutableListOf<CapturedMessage>()

    private fun importer(ignoredSamples: List<String> = emptyList()): ImportPastedMessagesUseCase {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val ingest = IngestCapturedMessageUseCase(
            customRules = CompiledRuleCache(FakeRuleRepository(), scope),
            transactionRepository = repository,
            onGate1Miss = { forwardedToAi += it },
            fallbackCurrencyProvider = { "AED" },
            ignoredMessages = IgnoredMessageCache(FakeIgnoredMessageRepository(ignoredSamples), scope),
        )
        return ImportPastedMessagesUseCase(ingest, repository, zone = utc)
    }

    /** Matches a built-in rule, so it parses without any custom rule configured. */
    private fun purchase(amount: String, extra: String = "") =
        "Payment of AED $amount at CARREFOUR was made from your account *617$extra"

    @Test
    fun `each pasted message becomes a transaction`() = runBlocking {
        val blob = "${purchase("25.00")}\n\n${purchase("40.00")}"
        val summary = importer().invoke(blob, now)
        assertEquals(2, summary.imported)
        assertEquals(2, summary.total)
        assertEquals(0, summary.skippedDuplicates)
    }

    @Test
    fun `pasted messages are recorded as PASTED, not as SMS`() = runBlocking {
        importer().invoke(purchase("25.00"), now)
        assertEquals(TransactionSource.PASTED, repository.recorded.single().source)
    }

    @Test
    fun `re-pasting the same blob imports nothing the second time`() = runBlocking {
        val blob = "${purchase("25.00")}\n\n${purchase("40.00")}"
        val importer = importer()
        assertEquals(2, importer.invoke(blob, now).imported)

        val second = importer.invoke(blob, now)
        assertEquals(0, second.imported)
        assertEquals(2, second.skippedDuplicates)
    }

    @Test
    fun `a message repeated inside one paste is imported once`() = runBlocking {
        val blob = "${purchase("25.00")}\n\n${purchase("25.00")}"
        val summary = importer().invoke(blob, now)
        assertEquals(1, summary.imported)
        assertEquals(1, summary.skippedDuplicates)
    }

    @Test
    fun `a message already in the ledger is skipped`() = runBlocking {
        val text = purchase("25.00")
        repository.preexisting += text
        val summary = importer().invoke(text, now)
        assertEquals(0, summary.imported)
        assertEquals(1, summary.skippedDuplicates)
        assertTrue(repository.recorded.isEmpty())
    }

    @Test
    fun `messages matching a muted template are counted as ignored`() = runBlocking {
        val promo = "Enjoy 50% off at Noon this weekend. Use code SAVE50. T&C apply."
        val blob = "${purchase("25.00")}\n\nEnjoy 30% off at Noon this weekend. Use code SAVE30. T&C apply."
        val summary = importer(ignoredSamples = listOf(promo)).invoke(blob, now)
        assertEquals(1, summary.imported)
        assertEquals(1, summary.ignored)
    }

    @Test
    fun `text no rule recognizes is counted as unparsed`() = runBlocking {
        val summary = importer().invoke("just some numbers 12345 with no shape", now)
        assertEquals(0, summary.imported)
        assertEquals(1, summary.unparsed)
    }

    @Test
    fun `an unreadable pasted message never spends AI budget`() = runBlocking {
        // Currency-adjacent amount, so a live capture of this WOULD clear
        // AiForwardGate and reach Gate 2. A paste is a deliberate batch, so it
        // must report unparsed instead of quietly draining the day's budget.
        val summary = importer().invoke("Some AED 42.00 shaped text no rule matches", now)
        assertEquals(1, summary.unparsed)
        assertTrue(forwardedToAi.isEmpty())
    }

    @Test
    fun `a whole batch of unreadable messages still spends nothing`() = runBlocking {
        val blob = (1..20).joinToString("\n") { "Some AED $it.00 shaped text no rule matches" }
        val summary = importer().invoke(blob, now)
        assertEquals(20, summary.unparsed)
        assertTrue(forwardedToAi.isEmpty())
    }

    @Test
    fun `the date printed in the message becomes the transaction's timestamp`() = runBlocking {
        importer().invoke(purchase("25.00", extra = " on 12/06/2025"), now)
        val expected = LocalDateTime.of(2025, 6, 12, 0, 0).atZone(utc).toInstant().toEpochMilli()
        assertEquals(expected, repository.recorded.single().timestampMillis)
    }

    @Test
    fun `a message with no date falls back to the import time`() = runBlocking {
        importer().invoke(purchase("25.00"), now)
        assertEquals(now, repository.recorded.single().timestampMillis)
    }

    @Test
    fun `the window dedup runs only for a message that states a clock time`() = runBlocking {
        // With a real time the ±2-minute check is meaningful, so it stays on;
        // with only a date (or nothing) it would compare midnight against
        // midnight and could discard a genuine second purchase that day.
        val importer = importer()
        importer.invoke(purchase("25.00", extra = " on 12/06/2025 14:23"), now)
        assertTrue(repository.dedupWindows.single())

        importer.invoke(purchase("40.00", extra = " on 13/06/2025"), now)
        assertFalse(repository.dedupWindows.last())

        importer.invoke(purchase("55.00"), now)
        assertFalse(repository.dedupWindows.last())
    }

    @Test
    fun `an empty paste does nothing`() = runBlocking {
        val summary = importer().invoke("   \n\n  ", now)
        assertEquals(0, summary.total)
        assertTrue(repository.recorded.isEmpty())
    }
}
