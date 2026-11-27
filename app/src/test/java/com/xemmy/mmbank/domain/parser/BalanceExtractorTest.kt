package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceExtractorTest {

    private fun extract(body: String, currency: String = "AED") =
        BalanceExtractor.extract(TextNormalizer.normalize(body), currency)

    private fun assertBalance(expected: String, kind: ReportedBalanceKind, actual: BalanceExtractor.ReportedBalance?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual!!.value))
        assertEquals(kind, actual.kind)
    }

    @Test
    fun `available account balance`() {
        assertBalance(
            "46120.85", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("Dear Customer, AED 1895.00 was debited from your account *617. Your available account balance is AED 46120.85"),
        )
    }

    @Test
    fun `available card balance is credit still available`() {
        assertBalance(
            "17296.72", ReportedBalanceKind.AVAILABLE_CREDIT,
            extract("Trx. of AED68.00 on your card ending *876 at MCDONALDS-54176, UAE is Approved. Avl. card bal is 17296.72. Trx Date: 17/07/26 13:35"),
        )
    }

    @Test
    fun `tabby card limit is available credit and tabby cash balance is a balance`() {
        assertBalance(
            "6676.12", ReportedBalanceKind.AVAILABLE_CREDIT,
            extract("Transaction of AED 20.00 At Tips and Toes was successful. Your available Tabby Card limit is AED 6676.12."),
        )
        assertBalance(
            "6111.71", ReportedBalanceKind.AVAILABLE_CREDIT,
            extract("You spent AED 110.90 at CAREEM RIDE. Your available Tabby Card limit is now AED 6111.71."),
        )
        assertBalance(
            "1675.59", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("You spent AED 32.95 at CARREFOUR-MAF MKT YAS. Your available Tabby Cash balance is now AED 1675.59."),
        )
    }

    @Test
    fun `arabic balance phrasings, with and without a currency`() {
        assertBalance(
            "106494.95", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("لقد تمّ تحويل راتبك AED62350.00 إلى حسابك رقم XXX810001 بتاريخ Jun 25 2026 6:23AM. الرصيد المتوفر 106494.95."),
        )
        assertBalance(
            "44081.95", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("تمت عملية Cr بمبلغ AED1880.39 على حسابك رقم XXX810001 بنجاح. رصيد حسابك الحالي AED44081.95"),
        )
        assertBalance(
            "35994.95", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("تم بنجاح إنجاز المعاملة رقم: Dr. مبلغ: AED 5500.00 على حسابك رقم. XXX810001 والرصيد المتاح هو AED 35994.95."),
        )
    }

    @Test
    fun `arabic-indic digits are read after normalization`() {
        assertBalance("1234.50", ReportedBalanceKind.ACCOUNT_BALANCE, extract("الرصيد المتوفر ١٢٣٤٫٥٠"))
    }

    @Test
    fun `thousands separators and a negative balance`() {
        assertBalance("12345.60", ReportedBalanceKind.ACCOUNT_BALANCE, extract("Your available balance is AED 12,345.60"))
        assertBalance("-250.00", ReportedBalanceKind.ACCOUNT_BALANCE, extract("Your available balance is AED -250.00"))
    }

    @Test
    fun `plain balance needs a currency, so prose is never taken`() {
        assertBalance("100", ReportedBalanceKind.ACCOUNT_BALANCE, extract("Balance: AED 100"))
        assertNull(extract("Please check your balance 3 times a day"))
    }

    @Test
    fun `no balance in the message`() {
        assertNull(extract("You spent AED 250.75 at CARREFOUR DUBAI."))
        assertNull(extract("Payment of AED 250.00 at CARREFOUR was made from your account *617"))
    }

    @Test
    fun `a balance in another currency than the transaction is dropped`() {
        assertNull(extract("You spent USD 10 at Amazon. Your available balance is AED 5,000.00", currency = "USD"))
    }

    @Test
    fun `the first balance statement wins`() {
        assertBalance(
            "100.00", ReportedBalanceKind.ACCOUNT_BALANCE,
            extract("Your available balance is AED 100.00. Current balance AED 999.00"),
        )
    }

    @Test
    fun `the parser keeps the amount and adds the balance`() {
        val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "USD")
        val r = parser.parse(
            CapturedMessage(
                source = TransactionSource.SMS,
                sender = "BANK",
                title = null,
                body = "Dear Customer, AED 1895.00 was debited from your account *617. Your available account balance is AED 46120.85",
                timestampMillis = 1,
            )
        )
        assertNotNull(r)
        // The amount is still the transaction's, never the trailing balance.
        assertEquals(0, BigDecimal("1895.00").compareTo(r!!.amount))
        assertEquals(0, BigDecimal("46120.85").compareTo(r.reportedBalance))
        assertEquals(ReportedBalanceKind.ACCOUNT_BALANCE, r.reportedBalanceKind)
    }

    @Test
    fun `the parser leaves the balance empty when the message carries none`() {
        val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "USD")
        val r = parser.parse(
            CapturedMessage(TransactionSource.SMS, "BANK", null, "You spent AED 250.75 at CARREFOUR DUBAI.", 1)
        )
        assertNotNull(r)
        assertNull(r!!.reportedBalance)
        assertNull(r.reportedBalanceKind)
    }
}
