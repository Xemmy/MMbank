package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementCsvParserTest {

    private fun lines(csv: String) = StatementCsvParser.parse(csv)

    @Test
    fun `a headed CSV with one signed amount column`() {
        val r = lines("Date,Description,Amount\n2026-11-01,NOON,-120.50\n2026-11-02,SALARY,5000.00")
        assertEquals(2, r.lines.size)
        assertEquals(LocalDate.of(2026, 11, 1), r.lines[0].date)
        assertEquals("NOON", r.lines[0].description)
        assertEquals(0, r.lines[0].amount.compareTo(BigDecimal("-120.50")))
        assertEquals(0, r.lines[1].amount.compareTo(BigDecimal("5000")))
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun `separate debit and credit columns become signed amounts`() {
        val r = lines("Date,Narration,Debit,Credit,Balance\n01/11/2026,CARREFOUR,45.00,,955.00\n02/11/2026,SALARY,,5000.00,5955.00")
        assertEquals(0, r.lines[0].amount.compareTo(BigDecimal("-45")))
        assertEquals(0, r.lines[1].amount.compareTo(BigDecimal("5000")))
        assertEquals(LocalDate.of(2026, 11, 1), r.lines[0].date)
    }

    @Test
    fun `semicolons, tabs and quoted fields containing the delimiter`() {
        val semi = lines("Date;Details;Amount\n01.11.2026;LULU;-10,50".replace("10,50", "10.50"))
        assertEquals(1, semi.lines.size)
        val tab = lines("Date\tDescription\tAmount\n2026-11-03\tCAFE\t-8.00")
        assertEquals(1, tab.lines.size)
        val quoted = lines("Date,Description,Amount\n2026-11-04,\"AMAZON, UAE\",\"-1,250.00\"")
        assertEquals("AMAZON, UAE", quoted.lines.single().description)
        assertEquals(0, quoted.lines.single().amount.compareTo(BigDecimal("-1250")))
    }

    @Test
    fun `Debit Amount and Credit Amount headers are the split columns, not one signed amount`() {
        val r = lines("Date,Description,Debit Amount,Credit Amount,Balance\n2026-11-01,LULU,30.00,,970.00\n2026-11-02,REFUND,,12.00,982.00")
        assertEquals(listOf("-30", "12"), r.lines.map { it.amount.stripTrailingZeros().toPlainString() })
    }

    @Test
    fun `parentheses and DR CR markers set the sign`() {
        val r = lines("Date,Description,Amount\n2026-11-01,A,(30.00)\n2026-11-02,B,12.00 DR\n2026-11-03,C,40.00 CR\n2026-11-04,D,AED 5.00")
        assertEquals(listOf("-30", "-12", "40", "5"), r.lines.map { it.amount.stripTrailingZeros().toPlainString() })
    }

    @Test
    fun `date formats seen on UAE statements`() {
        fun d(s: String) = StatementCsvParser.parseDate(s)
        assertEquals(LocalDate.of(2026, 11, 1), d("2026-11-01"))
        assertEquals(LocalDate.of(2026, 11, 1), d("01/11/2026"))
        assertEquals(LocalDate.of(2026, 11, 1), d("01-Nov-2026"))
        assertEquals(LocalDate.of(2026, 11, 1), d("1 Nov 2026"))
        assertEquals(LocalDate.of(2026, 11, 1), d("2026-11-01 14:30:00"))
        assertEquals(LocalDate.of(2026, 1, 13), d("01/13/2026")) // only valid month-first
        assertNull(d("120.50"))
        assertNull(d("NOON"))
    }

    @Test
    fun `without a header the first number after the date is the amount, not a trailing balance`() {
        val r = lines("2026-11-01,GYM,-200.00,4800.00")
        assertEquals(0, r.lines.single().amount.compareTo(BigDecimal("-200")))
        assertEquals("GYM", r.lines.single().description)
    }

    @Test
    fun `a description that starts with letters and digits is not read as an amount`() {
        assertNull(StatementCsvParser.parseAmount("ATM 1234"))
        assertEquals(0, StatementCsvParser.parseAmount("AED 1,234.50")!!.compareTo(BigDecimal("1234.50")))
    }

    @Test
    fun `unreadable lines are reported, not dropped silently`() {
        val r = lines("Account Statement\nDate,Description,Amount\n2026-11-01,NOON,-5.00\nTotal,,")
        assertEquals(1, r.lines.size)
        assertEquals(2, r.skipped.size)
    }

    @Test
    fun `empty input gives nothing`() {
        assertTrue(lines("  \n ").lines.isEmpty())
    }
}

class StatementReconcilerTest {

    private val utc = ZoneOffset.UTC

    private fun tx(date: String, amount: String, merchant: String? = "Shop", credit: Boolean = false, kind: TransactionKind? = null) =
        Transaction(
            amount = BigDecimal(amount), currencyCode = "AED",
            direction = if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
            merchant = merchant, source = TransactionSource.SMS,
            timestampMillis = LocalDate.parse(date).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
        ).let { if (kind != null) it.copy(kind = kind) else it }

    private fun line(date: String, amount: String, text: String = "") =
        StatementLine(LocalDate.parse(date), text, BigDecimal(amount))

    private fun run(lines: List<StatementLine>, txs: List<Transaction>, tolerance: Int = 3) =
        StatementReconciler.reconcile(lines, txs, tolerance, utc)

    @Test
    fun `an identical amount on the same day matches`() {
        val r = run(listOf(line("2026-11-01", "-120.50")), listOf(tx("2026-11-01", "120.50")))
        assertEquals(1, r.matched.size)
        assertTrue(r.isClean)
    }

    @Test
    fun `a bank that posts a couple of days later still matches`() {
        val r = run(listOf(line("2026-11-04", "-50")), listOf(tx("2026-11-02", "50")))
        assertEquals(1, r.matched.size)
    }

    @Test
    fun `outside the tolerance it is missing and the ledger row is extra`() {
        val r = run(listOf(line("2026-11-10", "-50")), listOf(tx("2026-11-02", "50")))
        assertEquals(1, r.missing.size)
        assertTrue(r.matched.isEmpty())
        // The ledger row is nowhere near the statement's dates, so it isn't reported as extra either.
        assertTrue(r.extra.isEmpty())
    }

    @Test
    fun `direction must agree`() {
        val r = run(listOf(line("2026-11-01", "-75")), listOf(tx("2026-11-01", "75", credit = true)))
        assertEquals(1, r.missing.size)
        assertEquals(1, r.extra.size)
    }

    @Test
    fun `two identical purchases match two identical lines one-to-one`() {
        val lines = listOf(line("2026-11-01", "-4"), line("2026-11-01", "-4"))
        val txs = listOf(tx("2026-11-01", "4"), tx("2026-11-01", "4"))
        val r = run(lines, txs)
        assertEquals(2, r.matched.size)
        assertTrue(r.isClean)
    }

    @Test
    fun `one ledger row cannot satisfy two statement lines`() {
        val lines = listOf(line("2026-11-01", "-4"), line("2026-11-01", "-4"))
        val r = run(lines, listOf(tx("2026-11-01", "4")))
        assertEquals(1, r.matched.size)
        assertEquals(1, r.missing.size)
    }

    @Test
    fun `the nearest date wins, then the closer description`() {
        val near = tx("2026-11-02", "30", merchant = "Other")
        val far = tx("2026-11-04", "30", merchant = "Noon")
        val r = run(listOf(line("2026-11-02", "-30", "NOON ONLINE")), listOf(far, near))
        assertEquals(near.timestampMillis, r.matched.single().transaction.timestampMillis)

        val a = tx("2026-11-02", "30", merchant = "Lulu")
        val b = tx("2026-11-02", "30", merchant = "Noon")
        val r2 = run(listOf(line("2026-11-02", "-30", "NOON ONLINE")), listOf(a, b))
        assertEquals("Noon", r2.matched.single().transaction.merchant)
    }

    @Test
    fun `extra rows are those inside the statement dates that nothing matched`() {
        val lines = listOf(line("2026-11-01", "-10"), line("2026-11-10", "-20"))
        val txs = listOf(tx("2026-11-01", "10"), tx("2026-11-05", "99", merchant = "Ghost"), tx("2026-12-20", "7"))
        val r = run(lines, txs)
        assertEquals(listOf("Ghost"), r.extra.map { it.merchant })
        assertEquals(1, r.missing.size) // the 20.00 line
    }

    @Test
    fun `balance adjustments are never reported as extra`() {
        val r = run(
            listOf(line("2026-11-01", "-10")),
            listOf(tx("2026-11-01", "10"), tx("2026-11-01", "3", kind = TransactionKind.ADJUSTMENT)),
        )
        assertTrue(r.isClean)
    }

    @Test
    fun `no statement lines means nothing to compare`() {
        val r = run(emptyList(), listOf(tx("2026-11-01", "10")))
        assertTrue(r.matched.isEmpty() && r.missing.isEmpty() && r.extra.isEmpty())
    }
}
