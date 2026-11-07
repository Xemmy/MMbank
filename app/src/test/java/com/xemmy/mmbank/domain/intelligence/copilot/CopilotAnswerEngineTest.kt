package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotAnswerEngineTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 7)
    private val thisMonth = DateWindow(LocalDate.of(2026, 11, 1), today)

    private fun cat(id: Long, name: String, parent: Long? = null) =
        Category(id = id, name = name, icon = "", colorHex = "#000000", parentCategoryId = parent)

    private val categories = listOf(cat(1, "Food"), cat(2, "Dining"), cat(3, "Groceries"), cat(5, "Coffee", parent = 1))

    private fun tx(
        amount: String,
        day: LocalDate,
        kind: TransactionKind = TransactionKind.SPEND,
        merchant: String? = null,
        categoryId: Long? = null,
        currency: String = "AED",
        excluded: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = if (kind == TransactionKind.INCOME || kind == TransactionKind.REFUND) {
            TransactionDirection.CREDIT
        } else {
            TransactionDirection.DEBIT
        },
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = day.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli(),
        categoryId = categoryId,
        excludeFromStats = excluded,
        kind = kind,
    )

    /** AED converts 1:1; anything else has no rate. */
    private val toBase: (Transaction) -> Double? = { if (it.currencyCode == "AED") it.amount.toDouble() else null }

    private fun nov(day: Int) = LocalDate.of(2026, 11, day)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)

    private val ledger = listOf(
        tx("5000", nov(1), TransactionKind.INCOME, merchant = "Employer"),
        tx("100", nov(3), merchant = "Zuma", categoryId = 2),
        tx("50", nov(4), merchant = "Carrefour", categoryId = 3),
        tx("20", nov(5), TransactionKind.REFUND, merchant = "Zuma", categoryId = 2),
        tx("500", nov(5), TransactionKind.TRANSFER),
        tx("1200", nov(5), TransactionKind.CARD_PAYMENT),
        tx("999", nov(5), merchant = "Hidden", categoryId = 3, excluded = true),
        tx("100", oct(5), merchant = "Zuma", categoryId = 2),
        tx("900", oct(20), merchant = "IKEA", categoryId = 3),
    )

    private fun ask(
        intent: CopilotIntent,
        subject: CopilotSubject? = null,
        window: DateWindow = thisMonth,
        label: String = "this month",
        rows: List<Transaction> = ledger,
    ) = CopilotAnswerEngine.answer(
        query = CopilotQuery(intent, window, label, subject),
        transactions = rows,
        categories = categories,
        toBase = toBase,
        currencyCode = "AED",
        today = today,
        zone = zone,
    )

    private val dining = CopilotSubject.CategorySubject(2, "Dining", setOf(2L))

    @Test
    fun `spend counts purchases, nets refunds, ignores transfers, card payments and excluded rows`() {
        val a = ask(CopilotIntent.SPEND)
        assertEquals("You spent AED 130.00 this month (2 purchases).", a.text)
        assertEquals(thisMonth.start, a.drillDown!!.from)
        assertEquals(thisMonth.end, a.drillDown!!.to)
    }

    @Test
    fun `spend on a category drills into that category`() {
        val a = ask(CopilotIntent.SPEND, dining)
        assertEquals("You spent AED 80.00 on Dining this month (1 purchase).", a.text)
        assertEquals(setOf(2L), a.drillDown!!.categoryIds)
    }

    @Test
    fun `a parent category includes its children`() {
        val rows = listOf(
            tx("12", nov(2), merchant = "Starbucks", categoryId = 5),
            tx("30", nov(2), merchant = "Bakery", categoryId = 1),
            tx("100", nov(2), merchant = "Zuma", categoryId = 2),
        )
        val food = CopilotSubject.CategorySubject(1, "Food", setOf(1L, 5L))
        assertEquals("You spent AED 42.00 on Food this month (2 purchases).", ask(CopilotIntent.SPEND, food, rows = rows).text)
    }

    @Test
    fun `merchant subject matches spellings by phrase`() {
        val rows = listOf(
            tx("25", nov(2), merchant = "CAREEM RIDE"),
            tx("15", nov(3), merchant = "Careem"),
            tx("30", nov(3), merchant = "Uber"),
        )
        val careem = CopilotSubject.MerchantSubject("Careem", "careem", "careem")
        val a = ask(CopilotIntent.SPEND, careem, rows = rows)
        assertEquals("You spent AED 40.00 at Careem this month (2 purchases).", a.text)
        assertEquals("careem", a.drillDown!!.query)
    }

    @Test
    fun `rows without an exchange rate are reported, not counted as zero`() {
        val rows = ledger + tx("40", nov(6), merchant = "Paris Cafe", currency = "EUR") +
            tx("10", nov(6), merchant = "Paris Cafe", currency = "EUR")
        val a = ask(CopilotIntent.SPEND, rows = rows)
        assertEquals(
            "You spent AED 130.00 this month (4 purchases). " +
                "Not counted: 2 EUR transactions (no exchange rate yet).",
            a.text,
        )
    }

    @Test
    fun `empty range`() {
        assertEquals("You haven't spent anything this month.", ask(CopilotIntent.SPEND, rows = emptyList()).text)
        assertEquals("Nothing spent on Dining this month.", ask(CopilotIntent.SPEND, dining, rows = emptyList()).text)
    }

    @Test
    fun `income`() {
        assertEquals("You received AED 5,000.00 this month (1 payment in).", ask(CopilotIntent.INCOME).text)
        assertEquals("No money received this month.", ask(CopilotIntent.INCOME, rows = emptyList()).text)
    }

    @Test
    fun `top categories with shares, drilling into the top one`() {
        val a = ask(CopilotIntent.TOP_CATEGORIES)
        assertEquals("Your top categories this month: Dining AED 80.00 (62%), Groceries AED 50.00 (38%).", a.text)
        assertEquals(setOf(2L), a.drillDown!!.categoryIds)
    }

    @Test
    fun `top merchants`() {
        val a = ask(CopilotIntent.TOP_MERCHANTS)
        assertEquals("Where your money went this month: Zuma AED 80.00 (1×), Carrefour AED 50.00 (1×).", a.text)
        assertEquals("zuma", a.drillDown!!.query)
    }

    @Test
    fun `biggest expense drills to its day`() {
        val a = ask(CopilotIntent.BIGGEST_EXPENSE)
        assertEquals("Your biggest expense this month was AED 100.00 at Zuma on 3 Nov.", a.text)
        assertEquals(nov(3), a.drillDown!!.from)
        assertEquals(nov(3), a.drillDown!!.to)
    }

    @Test
    fun `month to date compares with the same days last month`() {
        val a = ask(CopilotIntent.COMPARE)
        assertEquals("You spent AED 130.00 this month, 30% more than the AED 100.00 in the same days of October.", a.text)
    }

    @Test
    fun `compare with nothing before`() {
        val rows = listOf(tx("60", nov(2), categoryId = 2))
        assertEquals("You spent AED 60.00 this month, and nothing in the same days of October.", ask(CopilotIntent.COMPARE, rows = rows).text)
        assertEquals("No spending this month or in the same days of October.", ask(CopilotIntent.COMPARE, rows = emptyList()).text)
    }

    @Test
    fun `previous windows`() {
        val full = CopilotAnswerEngine.previousWindow(DateWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), today)
        assertEquals(DateWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), full.first)
        assertEquals("in August", full.second)

        val march31 = CopilotAnswerEngine.previousWindow(DateWindow(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)), today)
        assertEquals(DateWindow(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), march31.first)

        val week = CopilotAnswerEngine.previousWindow(DateWindow(nov(2), today), today)
        assertEquals(DateWindow(LocalDate.of(2026, 10, 26), oct(31)), week.first)
        assertEquals("in the same days of the week before", week.second)

        val year = CopilotAnswerEngine.previousWindow(DateWindow(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), today)
        assertEquals(DateWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)), year.first)
        assertEquals("in 2024", year.second)

        val tenDays = CopilotAnswerEngine.previousWindow(DateWindow(oct(29), today), today)
        assertEquals(DateWindow(oct(19), oct(28)), tenDays.first)
        assertEquals("in the 10 days before", tenDays.second)

        val january = CopilotAnswerEngine.previousWindow(DateWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), today)
        assertEquals(DateWindow(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)), january.first)
        assertEquals("in December 2025", january.second)
    }

    @Test
    fun `savings rate`() {
        assertEquals(
            "This month, you received AED 5,000.00 and spent AED 130.00: you kept 97% (AED 4,870.00).",
            ask(CopilotIntent.SAVINGS_RATE).text,
        )
        val noIncome = ledger.filter { it.kind != TransactionKind.INCOME }
        assertEquals(
            "No money received this month, so there's no savings rate yet; you spent AED 130.00.",
            ask(CopilotIntent.SAVINGS_RATE, rows = noIncome).text,
        )
        val overspent = listOf(tx("100", nov(1), TransactionKind.INCOME), tx("150", nov(2)))
        assertEquals(
            "This month, you spent AED 150.00, AED 50.00 more than the AED 100.00 you received.",
            ask(CopilotIntent.SAVINGS_RATE, rows = overspent).text,
        )
    }

    @Test
    fun `count and daily average`() {
        assertEquals("You made 2 purchases this month.", ask(CopilotIntent.TRANSACTION_COUNT).text)
        assertEquals("You made 1 purchase on Dining this month.", ask(CopilotIntent.TRANSACTION_COUNT, dining).text)
        // 130 over 7 days (1-7 Nov).
        assertEquals(
            "You spent AED 18.57 a day on average this month (AED 130.00 over 7 days).",
            ask(CopilotIntent.AVERAGE_PER_DAY).text,
        )
    }

    @Test
    fun `answers never claim rows outside the window`() {
        val october = DateWindow(oct(1), oct(31))
        val a = ask(CopilotIntent.SPEND, window = october, label = "in October")
        assertTrue(a.text, a.text.startsWith("You spent AED 1,000.00 in October"))
    }
}
