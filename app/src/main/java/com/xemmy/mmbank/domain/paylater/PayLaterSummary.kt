package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the Pay Later screen, the Plan hub card and Upcoming payments show, derived from the
 * saved plans and installments. Pure and JVM-testable.
 */
object PayLaterSummary {

    /** One installment with the plan it belongs to and where it sits in time. */
    data class Row(
        val plan: PayLaterPlan,
        val installment: PayLaterInstallment,
        val dueDate: LocalDate,
        /** Whole days from today; negative once overdue. */
        val daysUntil: Long,
    ) {
        /** "Payment 2 of 4". */
        val label: String get() = "Payment ${installment.number} of ${plan.installmentCount}"
    }

    /** The next date money is due, and how much falls on it in [currencyCode]. */
    data class NextDue(val dueDate: LocalDate, val daysUntil: Long, val amount: BigDecimal, val currencyCode: String)

    private fun rows(
        plans: List<PayLaterPlan>,
        installments: List<PayLaterInstallment>,
        today: LocalDate,
        zone: ZoneId,
        paid: Boolean,
    ): List<Row> {
        val byId = plans.associateBy { it.id }
        return installments.filter { it.isPaid == paid }.mapNotNull { i ->
            val plan = byId[i.planId] ?: return@mapNotNull null
            val due = Instant.ofEpochMilli(i.dueDateMillis).atZone(zone).toLocalDate()
            Row(plan, i, due, ChronoUnit.DAYS.between(today, due))
        }.sortedWith(
            compareBy<Row> { it.dueDate }.thenBy { it.plan.merchant.lowercase() }.thenBy { it.installment.number },
        )
    }

    /** Everything still to pay, earliest first (overdue included). */
    fun unpaid(plans: List<PayLaterPlan>, installments: List<PayLaterInstallment>, today: LocalDate, zone: ZoneId): List<Row> =
        rows(plans, installments, today, zone, paid = false)

    /** Everything already paid, most recently due first. */
    fun history(plans: List<PayLaterPlan>, installments: List<PayLaterInstallment>, today: LocalDate, zone: ZoneId): List<Row> =
        rows(plans, installments, today, zone, paid = true).reversed()

    /** The earliest due date among [unpaid] and what falls on it (in the first row's currency); null when nothing is owed. */
    fun nextDue(unpaid: List<Row>): NextDue? {
        val first = unpaid.firstOrNull() ?: return null
        val currency = first.plan.currencyCode.uppercase()
        val sameDay = unpaid.filter { it.dueDate == first.dueDate && it.plan.currencyCode.uppercase() == currency }
        return NextDue(first.dueDate, first.daysUntil, sameDay.fold(BigDecimal.ZERO) { a, r -> a + r.installment.amount }, currency)
    }

    /** Total still owed per currency -- never summed across currencies. */
    fun totalsByCurrency(unpaid: List<Row>): Map<String, BigDecimal> =
        unpaid.groupBy { it.plan.currencyCode.uppercase() }
            .mapValues { (_, rs) -> rs.fold(BigDecimal.ZERO) { a, r -> a + r.installment.amount } }

    /** [rows] grouped by due date, in date order -- one card per date on the screen. */
    fun groupByDate(rows: List<Row>): List<Pair<LocalDate, List<Row>>> =
        rows.groupBy { it.dueDate }.toSortedMap().map { (d, rs) -> d to rs }

    /** True once every installment of the plan is paid. */
    fun isFinished(installments: List<PayLaterInstallment>): Boolean =
        installments.isNotEmpty() && installments.all { it.isPaid }

    /** Rows owed on or before today. */
    fun overdue(unpaid: List<Row>): List<Row> = unpaid.filter { it.daysUntil < 0 }

    /** "Due in 9 days", "Due tomorrow", "Due today", "Overdue by 2 days". */
    fun dueLabel(daysUntil: Long): String = when {
        daysUntil < 0L -> "Overdue by ${-daysUntil} ${if (daysUntil == -1L) "day" else "days"}"
        daysUntil == 0L -> "Due today"
        daysUntil == 1L -> "Due tomorrow"
        else -> "Due in $daysUntil days"
    }
}
