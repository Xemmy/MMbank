package com.xemmy.mmbank.domain.account

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Month-by-month payoff simulation for several debts under one monthly
 * budget, comparing the two classic strategies:
 *
 * - **Avalanche**: extra money goes to the highest-rate debt first. Never
 *   costs more interest.
 * - **Snowball**: extra money goes to the smallest balance first. Costs a
 *   little more, but clears debts sooner, which keeps people going.
 *
 * Each month: interest accrues on every open balance (APR / 12, rounded to
 * the cent), every minimum is paid, and whatever is left of the budget goes
 * to the strategy's target -- then the next. A paid-off debt's minimum rolls
 * into the extra automatically, because the budget stays the same.
 *
 * Exact (`BigDecimal`), pure and JVM-testable.
 */
object DebtPayoffSimulator {

    enum class Strategy { AVALANCHE, SNOWBALL }

    data class Debt(
        val id: Long,
        val name: String,
        val balance: BigDecimal,
        /** Annual rate in percent, e.g. 36.0. */
        val aprPercent: Double,
        val minimumPayment: BigDecimal,
    )

    enum class Outcome {
        PAID_OFF,

        /** The budget doesn't even cover the minimum payments. */
        BUDGET_BELOW_MINIMUMS,

        /** The budget doesn't cover the interest, so the debt grows forever. */
        NEVER_PAYS_OFF,

        /** Still not paid off after the month cap. */
        HIT_CAP,
    }

    data class DebtResult(
        val id: Long,
        val name: String,
        /** Month (1-based) the debt reaches zero; null if it never does within the run. */
        val payoffMonth: Int?,
        val interestPaid: BigDecimal,
    )

    data class Plan(
        val strategy: Strategy,
        val outcome: Outcome,
        /** Months until every debt is paid (meaningful for [Outcome.PAID_OFF]). */
        val months: Int,
        val totalInterest: BigDecimal,
        /** In payoff order; unpaid debts last. */
        val debts: List<DebtResult>,
    )

    const val MAX_MONTHS = 600

    fun simulate(
        debts: List<Debt>,
        monthlyBudget: BigDecimal,
        strategy: Strategy,
        maxMonths: Int = MAX_MONTHS,
    ): Plan {
        val open = debts.filter { it.balance.signum() > 0 }
        if (open.isEmpty()) return Plan(strategy, Outcome.PAID_OFF, 0, BigDecimal.ZERO.setScale(2), emptyList())

        val minimumsDue = open.sumOf { it.minimumPayment.max(BigDecimal.ZERO).min(it.balance) }
        if (monthlyBudget < minimumsDue) {
            return Plan(strategy, Outcome.BUDGET_BELOW_MINIMUMS, 0, BigDecimal.ZERO.setScale(2), open.map { DebtResult(it.id, it.name, null, BigDecimal.ZERO.setScale(2)) })
        }
        val firstMonthInterest = open.sumOf { monthlyInterest(it.balance, it.aprPercent) }
        if (monthlyBudget <= firstMonthInterest) {
            return Plan(strategy, Outcome.NEVER_PAYS_OFF, 0, BigDecimal.ZERO.setScale(2), open.map { DebtResult(it.id, it.name, null, BigDecimal.ZERO.setScale(2)) })
        }

        val balance = open.associate { it.id to it.balance.setScale(2, RoundingMode.HALF_UP) }.toMutableMap()
        val interest = open.associate { it.id to BigDecimal.ZERO.setScale(2) }.toMutableMap()
        val paidOffIn = mutableMapOf<Long, Int>()
        val byId = open.associateBy { it.id }

        var month = 0
        while (balance.values.any { it.signum() > 0 } && month < maxMonths) {
            month++
            val active = byId.values.filter { balance.getValue(it.id).signum() > 0 }

            active.forEach { d ->
                val i = monthlyInterest(balance.getValue(d.id), d.aprPercent)
                balance[d.id] = balance.getValue(d.id) + i
                interest[d.id] = interest.getValue(d.id) + i
            }

            var available = monthlyBudget
            active.forEach { d ->
                val pay = d.minimumPayment.max(BigDecimal.ZERO).min(balance.getValue(d.id)).min(available)
                balance[d.id] = balance.getValue(d.id) - pay
                available -= pay
            }

            for (d in order(active, balance, strategy)) {
                if (available.signum() <= 0) break
                val pay = available.min(balance.getValue(d.id))
                balance[d.id] = balance.getValue(d.id) - pay
                available -= pay
            }

            active.forEach { d ->
                if (balance.getValue(d.id).signum() <= 0 && d.id !in paidOffIn) paidOffIn[d.id] = month
            }
        }

        val results = open.map { DebtResult(it.id, it.name, paidOffIn[it.id], interest.getValue(it.id)) }
            .sortedWith(compareBy<DebtResult> { it.payoffMonth ?: Int.MAX_VALUE }.thenBy { it.id })
        val done = balance.values.all { it.signum() <= 0 }
        return Plan(
            strategy = strategy,
            outcome = if (done) Outcome.PAID_OFF else Outcome.HIT_CAP,
            months = month,
            totalInterest = interest.values.fold(BigDecimal.ZERO.setScale(2)) { acc, v -> acc + v },
            debts = results,
        )
    }

    /** Both strategies on the same inputs. */
    fun compare(debts: List<Debt>, monthlyBudget: BigDecimal): Pair<Plan, Plan> =
        simulate(debts, monthlyBudget, Strategy.AVALANCHE) to simulate(debts, monthlyBudget, Strategy.SNOWBALL)

    /** The smallest budget that pays every minimum this month. */
    fun minimumBudget(debts: List<Debt>): BigDecimal =
        debts.filter { it.balance.signum() > 0 }.sumOf { it.minimumPayment.max(BigDecimal.ZERO).min(it.balance) }

    private fun order(active: List<Debt>, balance: Map<Long, BigDecimal>, strategy: Strategy): List<Debt> = when (strategy) {
        Strategy.AVALANCHE -> active.sortedWith(
            compareByDescending<Debt> { it.aprPercent }.thenBy { balance.getValue(it.id) }.thenBy { it.id },
        )
        Strategy.SNOWBALL -> active.sortedWith(
            compareBy<Debt> { balance.getValue(it.id) }.thenByDescending { it.aprPercent }.thenBy { it.id },
        )
    }

    internal fun monthlyInterest(balance: BigDecimal, aprPercent: Double): BigDecimal =
        if (aprPercent <= 0.0 || balance.signum() <= 0) {
            BigDecimal.ZERO.setScale(2)
        } else {
            (balance * BigDecimal.valueOf(aprPercent))
                .divide(BigDecimal(1200), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP)
        }
}
