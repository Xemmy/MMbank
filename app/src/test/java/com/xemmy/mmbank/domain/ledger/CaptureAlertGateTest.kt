package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAlertGateTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun spend(
        source: TransactionSource = TransactionSource.SMS,
        ageMillis: Long = 0L,
        currency: String = "AED",
        categoryId: Long? = 7L,
    ) = Transaction(
        amount = BigDecimal("50.00"),
        currencyCode = currency,
        direction = TransactionDirection.DEBIT,
        source = source,
        timestampMillis = now - ageMillis,
        kind = TransactionKind.SPEND,
        categoryId = categoryId,
    )

    @Test fun aFreshSmsSpendInTheBaseCurrencyPacesTheBudget() {
        assertTrue(CaptureAlertGate.pacesDailyBudget(spend(), now, "AED"))
        assertTrue(CaptureAlertGate.pacesDailyBudget(spend(source = TransactionSource.PUSH_NOTIFICATION), now, " aed "))
    }

    @Test fun aSpendInAnotherCurrencyIsNotComparedWithTheBudget() {
        assertFalse(CaptureAlertGate.pacesDailyBudget(spend(currency = "JPY"), now, "AED"))
    }

    @Test fun pastedManualAndOldRowsRaiseNoAlert() {
        assertFalse(CaptureAlertGate.pacesDailyBudget(spend(source = TransactionSource.PASTED), now, "AED"))
        assertFalse(CaptureAlertGate.pacesDailyBudget(spend(source = TransactionSource.MANUAL), now, "AED"))
        assertFalse(CaptureAlertGate.pacesDailyBudget(spend(ageMillis = 30 * hour), now, "AED"))
        assertFalse(CaptureAlertGate.checksCategoryCap(spend(ageMillis = 30 * 24 * hour), now))
    }

    @Test fun theCategoryCapNeedsACategory() {
        assertTrue(CaptureAlertGate.checksCategoryCap(spend(), now))
        assertFalse(CaptureAlertGate.checksCategoryCap(spend(categoryId = null), now))
    }

    @Test fun aNotificationSlightlyAheadOfTheClockStillCounts() {
        assertTrue(CaptureAlertGate.isLiveAndFresh(spend(ageMillis = -2 * 60 * 1000), now))
        assertFalse(CaptureAlertGate.isLiveAndFresh(spend(ageMillis = -hour), now))
    }
}
