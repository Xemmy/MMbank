package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Category
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotQueryParserTest {

    /** A Saturday. The week (Monday start) began on 2 Nov. */
    private val today = LocalDate.of(2026, 11, 7)

    private fun cat(id: Long, name: String, parent: Long? = null) =
        Category(id = id, name = name, icon = "", colorHex = "#000000", parentCategoryId = parent)

    private val categories = listOf(
        cat(1, "Food"),
        cat(2, "Dining"),
        cat(3, "Groceries"),
        cat(4, "Transport"),
        cat(5, "Coffee", parent = 1),
        cat(6, "Income"),
        cat(7, "Uncategorized"),
    )
    private val merchants = listOf("Careem", "Carrefour Market", "Amazon", "Payment")

    private fun parse(q: String, cats: List<Category> = categories, ms: List<String> = merchants) =
        CopilotQueryParser.parse(q, cats, ms, today)

    private fun window(from: LocalDate, to: LocalDate) = DateWindow(from, to)

    // ------------------------------------------------------------- intents

    @Test
    fun `plain spend question defaults to this month`() {
        val q = parse("How much did I spend?")!!
        assertEquals(CopilotIntent.SPEND, q.intent)
        assertEquals(window(LocalDate.of(2026, 11, 1), today), q.window)
        assertEquals("this month", q.rangeLabel)
        assertNull(q.subject)
    }

    @Test
    fun `recognises each intent`() {
        val cases = mapOf(
            "What's my biggest expense this month?" to CopilotIntent.BIGGEST_EXPENSE,
            "largest purchase last week" to CopilotIntent.BIGGEST_EXPENSE,
            "top categories last month" to CopilotIntent.TOP_CATEGORIES,
            "Where did my money go?" to CopilotIntent.TOP_CATEGORIES,
            "what do I spend the most on" to CopilotIntent.TOP_CATEGORIES,
            "where do I spend the most" to CopilotIntent.TOP_MERCHANTS,
            "my top merchants this year" to CopilotIntent.TOP_MERCHANTS,
            "Compare this month with last month" to CopilotIntent.COMPARE,
            "did I spend more than last month" to CopilotIntent.COMPARE,
            "what is my savings rate" to CopilotIntent.SAVINGS_RATE,
            "how many purchases this week" to CopilotIntent.TRANSACTION_COUNT,
            "average daily spend" to CopilotIntent.AVERAGE_PER_DAY,
            "how much do I spend a day" to CopilotIntent.AVERAGE_PER_DAY,
            "how much did I receive in October" to CopilotIntent.INCOME,
            "salary last month" to CopilotIntent.INCOME,
            "total spent yesterday" to CopilotIntent.SPEND,
        )
        cases.forEach { (question, intent) ->
            assertEquals(question, intent, parse(question)?.intent)
        }
    }

    @Test
    fun `questions it cannot read return null`() {
        assertNull(parse("tell me a joke"))
        assertNull(parse(""))
        assertNull(parse("   ?!  "))
    }

    @Test
    fun `a subject alone is a spend question`() {
        val q = parse("dining last month")!!
        assertEquals(CopilotIntent.SPEND, q.intent)
        assertEquals(2L, (q.subject as CopilotSubject.CategorySubject).categoryId)
    }

    // -------------------------------------------------------------- ranges

    @Test
    fun `relative ranges`() {
        fun range(q: String) = CopilotQueryParser.parseRange(q, today)!!
        assertEquals(window(today, today), range("today").window)
        assertEquals(window(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6)), range("yesterday").window)
        assertEquals(window(LocalDate.of(2026, 11, 2), today), range("this week").window)
        assertEquals(window(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)), range("last week").window)
        assertEquals(window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), range("last month").window)
        assertEquals("in October", range("last month").label)
        assertEquals(window(LocalDate.of(2026, 1, 1), today), range("this year").window)
        assertEquals(window(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), range("last year").window)
        assertEquals("in 2025", range("last year").label)
    }

    @Test
    fun `last N days, weeks and months end today`() {
        val days = CopilotQueryParser.parseRange("spend in the last 7 days", today)!!
        assertEquals(window(LocalDate.of(2026, 11, 1), today), days.window)
        assertEquals("in the last 7 days", days.label)
        assertEquals(
            window(LocalDate.of(2026, 10, 18), today),
            CopilotQueryParser.parseRange("past 3 weeks", today)!!.window,
        )
        assertEquals(
            window(LocalDate.of(2026, 8, 8), today),
            CopilotQueryParser.parseRange("previous 3 months", today)!!.window,
        )
    }

    @Test
    fun `a month name means its latest occurrence that isn't in the future`() {
        val may = CopilotQueryParser.parseRange("how much in May", today)!!
        assertEquals(window(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)), may.window)
        assertEquals("in May", may.label)

        val dec = CopilotQueryParser.parseRange("december spending", today)!!
        assertEquals(window(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)), dec.window)
        assertEquals("in December 2025", dec.label)

        val oct = CopilotQueryParser.parseRange("oct 2025", today)!!
        assertEquals(window(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31)), oct.window)

        // The current month runs to today.
        assertEquals("this month", CopilotQueryParser.parseRange("november", today)!!.label)
    }

    @Test
    fun `bare may is not a month`() {
        assertNull(CopilotQueryParser.parseRange("may I know how much I spent", today))
        assertEquals(
            window(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31)),
            CopilotQueryParser.parseRange("may 2025", today)!!.window,
        )
    }

    @Test
    fun `february in a leap year has 29 days`() {
        val r = CopilotQueryParser.parseRange("february", LocalDate.of(2028, 3, 15))!!
        assertEquals(window(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)), r.window)
    }

    @Test
    fun `a year on its own`() {
        assertEquals(
            window(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
            CopilotQueryParser.parseRange("in 2024", today)!!.window,
        )
        assertEquals("this year", CopilotQueryParser.parseRange("for 2026", today)!!.label)
    }

    @Test
    fun `no range mentioned returns null`() {
        assertNull(CopilotQueryParser.parseRange("how much on dining", today))
    }

    // ------------------------------------------------------------- subjects

    @Test
    fun `category subject includes its sub-categories`() {
        val s = parse("how much on food this month")!!.subject as CopilotSubject.CategorySubject
        assertEquals(1L, s.categoryId)
        assertEquals(setOf(1L, 5L), s.categoryIds)
        assertEquals("Food", s.label)
    }

    @Test
    fun `plural and multi-word category names match`() {
        assertEquals(3L, (parse("grocery spend")!!.subject as CopilotSubject.CategorySubject).categoryId)
        val withAmpersand = listOf(cat(9, "Food & Dining"))
        val s = parse("how much on dining", withAmpersand)!!.subject as CopilotSubject.CategorySubject
        assertEquals(9L, s.categoryId)
    }

    @Test
    fun `everyday words reach the seeded category`() {
        val s = parse("how much on restaurants this week")!!
        assertEquals(2L, (s.subject as CopilotSubject.CategorySubject).categoryId)
        assertEquals(window(LocalDate.of(2026, 11, 2), today), s.window)
    }

    @Test
    fun `a typo still finds the category`() {
        assertEquals(3L, (parse("how much on grocries")!!.subject as CopilotSubject.CategorySubject).categoryId)
    }

    @Test
    fun `merchant subject`() {
        val s = parse("How much at Careem in October?")!!.subject as CopilotSubject.MerchantSubject
        assertEquals("careem", s.phrase)
        assertEquals("Careem", s.label)
    }

    @Test
    fun `a merchant named verbatim beats a category synonym`() {
        // "uber" is a Transport synonym, but here it's a merchant in the ledger.
        val s = parse("how much at uber", ms = listOf("Uber"))!!.subject
        assertTrue(s is CopilotSubject.MerchantSubject)
    }

    @Test
    fun `when both are named verbatim the longer phrase wins`() {
        val ms = listOf("Dining Hall")
        assertTrue(parse("how much at dining hall", ms = ms)!!.subject is CopilotSubject.MerchantSubject)
        assertTrue(parse("how much on dining", ms = ms)!!.subject is CopilotSubject.CategorySubject)
    }

    @Test
    fun `a merchant made of question words never becomes the subject`() {
        val q = parse("how much payment did I make this month")!!
        assertNull(q.subject)
    }

    @Test
    fun `income questions ignore the Income category and any subject`() {
        val q = parse("how much income this month")!!
        assertEquals(CopilotIntent.INCOME, q.intent)
        assertNull(q.subject)
    }

    @Test
    fun `top categories never narrows to one category`() {
        assertNull(parse("top categories in dining")!!.subject)
    }

    @Test
    fun `uncategorized is a category, not the word category`() {
        val q = parse("how much is uncategorized")!!
        assertEquals(CopilotIntent.SPEND, q.intent)
        assertEquals(7L, (q.subject as CopilotSubject.CategorySubject).categoryId)
    }

    @Test
    fun `count at a merchant keeps the subject`() {
        val q = parse("how many purchases at careem this week")!!
        assertEquals(CopilotIntent.TRANSACTION_COUNT, q.intent)
        assertTrue(q.subject is CopilotSubject.MerchantSubject)
    }

    @Test
    fun `within one edit`() {
        assertTrue(CopilotQueryParser.withinOneEdit("grocery", "grocry"))
        assertTrue(CopilotQueryParser.withinOneEdit("careem", "kareem"))
        assertTrue(CopilotQueryParser.withinOneEdit("amazon", "amazons"))
        assertFalse(CopilotQueryParser.withinOneEdit("amazon", "amzn"))
        assertFalse(CopilotQueryParser.withinOneEdit("dining", "diners"))
    }
}
