package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRetentionScorerTest {

    private fun tx(
        amount: String,
        category: SpendingCategory = SpendingCategory.UNCATEGORIZED,
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = 0L,
        spendingCategory = category,
        excludeFromStats = excludeFromStats,
    )

    @Test
    fun `amount far above average is significant`() {
        assertTrue(AiRetentionScorer.isSignificant(tx("1000"), averageMonthlyAmount = BigDecimal("100")))
    }

    @Test
    fun `ordinary amount near average is not significant`() {
        assertFalse(AiRetentionScorer.isSignificant(tx("120"), averageMonthlyAmount = BigDecimal("100")))
    }

    @Test
    fun `income category is always significant regardless of amount`() {
        assertTrue(
            AiRetentionScorer.isSignificant(
                tx("50", category = SpendingCategory.INCOME),
                averageMonthlyAmount = BigDecimal("1000"),
            )
        )
    }

    @Test
    fun `excluded-from-stats corrections are never significant`() {
        assertFalse(
            AiRetentionScorer.isSignificant(
                tx("5000", excludeFromStats = true),
                averageMonthlyAmount = BigDecimal("100"),
            )
        )
    }

    @Test
    fun `zero average never flags an anomaly`() {
        assertFalse(AiRetentionScorer.isSignificant(tx("500"), averageMonthlyAmount = BigDecimal.ZERO))
    }
}
