package com.xemmy.mmbank.domain.budget

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Real-time daily-spend pacing: how much is left to spend today, and whether
 * a just-recorded debit blew past it.
 *
 * Deliberately named apart from [com.xemmy.mmbank.domain.ai.AiDailyBudget] --
 * that object caps automatic Gate-2 AI *requests* per day and is unrelated to
 * money. This one paces the user's own *spending* against their monthly
 * budget, so the two can't be confused despite the similar-sounding name.
 *
 * Pure state-in/state-out logic, decoupled from persistence (AppPreferences)
 * and from wall-clock month boundaries (the caller supplies [LocalDate] /
 * remaining-days figures), so it's JVM-testable without Robolectric.
 */
object DailyBudgetPacing {

    /** Outcome of comparing a transaction against today's allowance. */
    sealed interface PacingResult {
        /** No monthly budget configured (<= 0) -- pacing is a no-op. */
        data object NoBudgetSet : PacingResult

        /** The transaction fit within today's allowance. */
        data class OnTrack(val dailyAllowance: BigDecimal) : PacingResult

        /** The transaction exceeded today's allowance by [overspendAmount]. */
        data class Overspend(val dailyAllowance: BigDecimal, val overspendAmount: BigDecimal) : PacingResult
    }

    /**
     * `(monthlyBudget - max(fixedExpenses, spentSoFar)) / remainingDaysInMonth`,
     * rounded to 2 decimal places. A month with no remaining days left (the
     * caller rolled past it without re-deriving [remainingDaysInMonth]) yields
     * zero rather than dividing by zero.
     */
    fun dailyAllowance(
        monthlyBudget: BigDecimal,
        fixedExpenses: BigDecimal,
        spentSoFar: BigDecimal,
        remainingDaysInMonth: Int,
    ): BigDecimal {
        if (remainingDaysInMonth <= 0) return BigDecimal.ZERO
        // Fixed expenses are money already committed; once paid they are part of
        // spentSoFar, so subtracting both counted them twice. Commit whichever is
        // larger: the unpaid fixed total, or what has actually gone out.
        val committed = fixedExpenses.max(spentSoFar)
        val remaining = monthlyBudget - committed
        return remaining.divide(BigDecimal(remainingDaysInMonth), 2, RoundingMode.HALF_UP)
    }

    /**
     * Checks whether [transactionAmount] -- already reflected in
     * [spentSoFarIncludingThis] -- pushed the day over its allowance. The
     * allowance is computed on spend *before* this transaction, so a
     * transaction that itself IS the overspend is measured against what was
     * actually available going in.
     */
    fun checkPacing(
        transactionAmount: BigDecimal,
        monthlyBudget: BigDecimal,
        fixedExpenses: BigDecimal,
        spentSoFarIncludingThis: BigDecimal,
        remainingDaysInMonth: Int,
    ): PacingResult {
        if (monthlyBudget <= BigDecimal.ZERO) return PacingResult.NoBudgetSet

        val spentBeforeThis = spentSoFarIncludingThis - transactionAmount
        val allowance = dailyAllowance(monthlyBudget, fixedExpenses, spentBeforeThis, remainingDaysInMonth)

        return if (transactionAmount > allowance) {
            PacingResult.Overspend(allowance, transactionAmount - allowance)
        } else {
            PacingResult.OnTrack(allowance)
        }
    }

    /** Days left in [today]'s month, counting [today] itself -- used to derive [remainingDaysInMonth]. */
    fun remainingDaysInMonth(today: LocalDate): Int = today.lengthOfMonth() - today.dayOfMonth + 1
}
