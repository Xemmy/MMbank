package com.xemmy.mmbank.ui.screens.search

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.ui.screens.plan.PlanEntry
import com.xemmy.mmbank.ui.screens.settings.SettingsEntry
import java.math.BigDecimal
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {

    private val utc = ZoneOffset.UTC
    private var nextTime = 1_700_000_000_000L

    private fun tx(merchant: String?, amount: String = "50.00", direction: TransactionDirection = TransactionDirection.DEBIT) =
        Transaction(
            amount = BigDecimal(amount), currencyCode = "AED", direction = direction, merchant = merchant,
            source = TransactionSource.SMS, timestampMillis = nextTime.also { nextTime += 60_000 },
        )

    private fun account(name: String, bank: String? = null, number: String? = null) =
        Account(name = name, type = AccountType.CHECKING, currencyCode = "AED", accountNumber = number, bankName = bank)

    private fun rule(id: Long, name: String, enabled: Boolean = true) =
        ParsingRule(id = id, name = name, pattern = "x", direction = TransactionDirection.DEBIT, enabled = enabled)

    private fun run(
        query: String,
        txs: List<Transaction> = emptyList(),
        accounts: List<Account> = emptyList(),
        rules: List<ParsingRule> = emptyList(),
        destinations: List<SearchDestination> = SearchDestinations.all,
    ) = GlobalSearch.search(query, txs, accounts, rules, destinations, utc)

    @Test
    fun `a blank or one-character query finds nothing`() {
        assertTrue(run("", txs = listOf(tx("Noon"))).isEmpty())
        assertTrue(run(" a ", txs = listOf(tx("Aster"))).isEmpty())
    }

    @Test
    fun `merchants are grouped by brand with a count and open the merchant screen`() {
        val result = run("carrefour", txs = listOf(tx("CARREFOUR DUBAI"), tx("Carrefour"), tx("Noon")))
            .single { it.kind == SearchKind.MERCHANT }
        assertEquals("2 transactions", result.subtitle)
        assertTrue(result.target is SearchTarget.Merchant)
    }

    @Test
    fun `an exact title outranks a prefix, which outranks a mid-word match`() {
        val rows = run("noon", txs = listOf(tx("Noon"), tx("Noon Food"), tx("Afternoon Cafe")))
            .filter { it.kind == SearchKind.MERCHANT }
        assertEquals(listOf("Noon", "Noon Food", "Afternoon Cafe"), rows.map { it.title })
    }

    @Test
    fun `every word has to match, in any order`() {
        val txs = listOf(tx("Union Coop Dubai"), tx("Union Bank"))
        val rows = run("coop union", txs = txs).filter { it.kind == SearchKind.MERCHANT }
        assertEquals(listOf("Union Coop Dubai"), rows.map { it.title })
    }

    @Test
    fun `a typed amount finds the transaction with exactly that amount`() {
        val rows = run("120.5", txs = listOf(tx("Noon", "120.50"), tx("Lulu", "1120.50"), tx("Zara", "20")))
            .filter { it.kind == SearchKind.TRANSACTION }
        assertEquals(listOf("Noon"), rows.map { it.title })
    }

    @Test
    fun `transaction rows show the signed amount and date`() {
        val row = run("noon", txs = listOf(tx("Noon", "12.50", TransactionDirection.CREDIT)))
            .single { it.kind == SearchKind.TRANSACTION }
        assertTrue(row.subtitle, row.subtitle.startsWith("+12.50 AED · 2023-11-14"))
    }

    @Test
    fun `accounts match by name, bank or the last four digits of the number`() {
        val accounts = listOf(account("Daily", bank = "ADCB", number = "9876541234"), account("Savings", bank = "ENBD"))
        assertEquals(listOf("Daily"), run("adcb", accounts = accounts).filter { it.kind == SearchKind.ACCOUNT }.map { it.title })
        assertEquals(listOf("Daily"), run("1234", accounts = accounts).filter { it.kind == SearchKind.ACCOUNT }.map { it.title })
        assertTrue(run("876", accounts = accounts).none { it.kind == SearchKind.ACCOUNT })
    }

    @Test
    fun `rules open the rule editor and say when they are off`() {
        val rows = run("adcb", rules = listOf(rule(7, "ADCB debit card", enabled = false)))
            .filter { it.kind == SearchKind.RULE }
        assertEquals(SearchTarget.Rule(7), rows.single().target)
        assertTrue(rows.single().subtitle.endsWith("off"))
    }

    @Test
    fun `screens and settings are found by title or by keyword`() {
        fun titles(q: String) = run(q).filter { it.kind == SearchKind.DESTINATION }.map { it.title }
        assertTrue(titles("budgets").contains("Budgets"))
        assertTrue(titles("pin").contains("App lock & PIN"))
        assertTrue(titles("forex").contains("Currency & preferences"))
        assertTrue(titles("export").contains("Backup & restore"))
    }

    @Test
    fun `a title match outranks a keyword-only match`() {
        val results = run("backup").filter { it.kind == SearchKind.DESTINATION }
        assertEquals("Backup & restore", results.first().title)
    }

    @Test
    fun `each kind is capped so one merchant cannot flood the list`() {
        val txs = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel").map { tx("Noon $it") }
        val rows = run("noon", txs = txs)
        assertEquals(GlobalSearch.PER_KIND_LIMIT, rows.count { it.kind == SearchKind.MERCHANT })
        assertEquals(GlobalSearch.PER_KIND_LIMIT, rows.count { it.kind == SearchKind.TRANSACTION })
    }

    @Test
    fun `every settings row and plan tool is searchable`() {
        assertEquals(SettingsEntry.entries.toSet(), SearchDestinations.settingsEntriesCovered)
        val routes = SearchDestinations.all.map { it.route }
        PlanEntry.entries.forEach { assertTrue(it.route, it.route in routes) }
        SettingsEntry.entries.forEach { assertTrue(it.route, it.route in routes) }
    }

    @Test
    fun `a transaction with no merchant opens Activity instead`() {
        val row = run("50", txs = listOf(tx(null, "50"))).single { it.kind == SearchKind.TRANSACTION }
        assertEquals(SearchTarget.Route("transactions"), row.target)
    }
}
