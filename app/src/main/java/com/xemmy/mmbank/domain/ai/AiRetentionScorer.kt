package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal

/**
 * Pure anomaly/importance scoring for a transaction, for a future backup
 * retention/pruning pass to consult before removing old rows. No such
 * pruning job exists in MMbank yet -- BackupManager only exports/imports
 * today -- so this ships the evaluation logic ready to wire in, without
 * inventing a scheduled-deletion subsystem that isn't part of the app.
 *
 * A transaction this flags as significant should be stepped over by any
 * future removal target rather than pruned.
 */
object AiRetentionScorer {

    fun isSignificant(
        transaction: Transaction,
        averageMonthlyAmount: BigDecimal,
        anomalyMultiplier: BigDecimal = BigDecimal("3"),
    ): Boolean {
        // Corrections/adjustments aren't real activity to protect -- they
        // exist to fix the ledger, not to record a distinct event.
        if (transaction.excludeFromStats) return false
        if (transaction.spendingCategory == SpendingCategory.INCOME) return true
        return isAmountAnomaly(transaction.amount, averageMonthlyAmount, anomalyMultiplier)
    }

    private fun isAmountAnomaly(amount: BigDecimal, average: BigDecimal, multiplier: BigDecimal): Boolean {
        if (average <= BigDecimal.ZERO) return false
        return amount > average.multiply(multiplier)
    }
}
