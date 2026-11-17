package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the installments of a "split into N payments" purchase. Pure and JVM-testable.
 *
 * The total is split evenly to the cent; the rounding remainder goes on the last installment so
 * the parts always add up to the total exactly. Due dates step from the first one by the cadence
 * (monthly steps are computed from the first date, so a 31st clamped to the 28th in February
 * returns to the 31st in March rather than staying on the 28th).
 */
object PayLaterSchedule {

    const val MIN_COUNT = 2
    const val MAX_COUNT = 36

    /** Millis of the start of [date] in [zone] -- how installment due dates are stored. */
    fun millisOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The [count] amounts: even to the cent, remainder on the last. */
    fun splitAmounts(total: BigDecimal, count: Int): List<BigDecimal> {
        require(count >= 1) { "count must be at least 1" }
        val each = total.divide(BigDecimal(count), 2, RoundingMode.DOWN)
        val last = total.setScale(2, RoundingMode.HALF_UP) - each * BigDecimal(count - 1)
        return List(count) { i -> if (i == count - 1) last else each }
    }

    /** The due date of installment [index] (0-based) given the [first] one. */
    fun dueDate(first: LocalDate, cadence: PayLaterCadence, index: Int): LocalDate = when (cadence) {
        PayLaterCadence.WEEKLY -> first.plusWeeks(index.toLong())
        PayLaterCadence.BIWEEKLY -> first.plusWeeks(2L * index)
        PayLaterCadence.MONTHLY -> first.plusMonths(index.toLong())
    }

    /**
     * The installments (unsaved, planId 0). With [firstPaid] the first one is already settled --
     * Tabby takes it at checkout -- and is stamped paid at its own due date, with no linked
     * transaction.
     */
    fun generate(
        total: BigDecimal,
        count: Int,
        firstDue: LocalDate,
        cadence: PayLaterCadence,
        zone: ZoneId = ZoneId.systemDefault(),
        firstPaid: Boolean = false,
    ): List<PayLaterInstallment> {
        val amounts = splitAmounts(total, count)
        return amounts.mapIndexed { i, amount ->
            val due = millisOf(dueDate(firstDue, cadence, i), zone)
            PayLaterInstallment(
                planId = 0,
                number = i + 1,
                amount = amount,
                dueDateMillis = due,
                paidAtMillis = if (firstPaid && i == 0) due else null,
            )
        }
    }
}
