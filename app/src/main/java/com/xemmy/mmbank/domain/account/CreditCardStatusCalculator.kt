package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Where a credit card stands: how much is owed, how much credit is left, what
 * the last statement asked for, how much of that has been paid, and when it's
 * due. Pure -- the caller supplies the card's computed balance (from
 * [AccountBalanceCalculator], the single source of truth) and its own
 * transactions.
 *
 * Statement cycle: [Account.statementCycleDay] is the day a cycle *starts*
 * (10 = "10th of last month -> 9th of this month"). The current cycle is the
 * one that started on the most recent such day; the statement is the balance
 * the moment it started. [Account.dueDayOfMonth] is the payment due day: the
 * first such day on or after the cycle start. Days past the end of a short
 * month clamp to its last day (a "31st" cycle starts on 28/29 Feb).
 *
 * Without a statement day the app can't know what the last statement asked
 * for, so everything owed is treated as due (marked [CardStatus.estimated]).
 */
object CreditCardStatusCalculator {

    enum class Status { NOTHING_OWED, PAID, DUE, DUE_SOON, OVERDUE, NO_DUE_DATE }

    /** How close a due date has to be to read as "due soon". */
    const val DUE_SOON_DAYS = 7L

    data class CardStatus(
        /** What's owed right now (never negative; a card in credit owes 0). */
        val outstanding: BigDecimal,
        val creditLimit: BigDecimal?,
        /** Limit minus outstanding; negative when over the limit. Null without a limit. */
        val available: BigDecimal?,
        /** Outstanding / limit, 0..1+ ; null without a limit. */
        val utilisation: Double?,
        val cycleStart: LocalDate?,
        /** What the last statement asked for; null without a statement day. */
        val statementBalance: BigDecimal?,
        /** Payments onto the card since the statement. */
        val paidSinceStatement: BigDecimal,
        /** Statement balance still unpaid (or everything owed, when [estimated]). */
        val amountDue: BigDecimal,
        val dueDate: LocalDate?,
        /** Whole days from today to [dueDate]; negative once overdue. */
        val daysUntilDue: Long?,
        /** Net spending on the card this cycle (refunds netted). */
        val spentThisCycle: BigDecimal,
        val status: Status,
        /** True when there's no statement day, so [amountDue] is simply everything owed. */
        val estimated: Boolean,
    )

    fun status(
        account: Account,
        balance: BigDecimal,
        transactions: List<Transaction>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CardStatus {
        val own = transactions.filter { it.accountId == account.id }
        val outstanding = balance.negate().max(BigDecimal.ZERO)
        val limit = account.creditLimit?.takeIf { it.signum() > 0 }
        val available = limit?.subtract(outstanding)
        val utilisation = limit?.let { outstanding.divide(it, 4, RoundingMode.HALF_UP).toDouble() }

        val cycleStart = account.statementCycleDay?.let { mostRecentDay(it, today) }
        fun onOrAfter(start: LocalDate, tx: Transaction) =
            !Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate().isBefore(start)

        val sinceCycle = cycleStart?.let { start -> own.filter { onOrAfter(start, it) } }
        val movementSince = sinceCycle.orEmpty().fold(BigDecimal.ZERO) { acc, tx ->
            if (tx.direction == TransactionDirection.CREDIT) acc + tx.amount else acc - tx.amount
        }
        val statementBalance = cycleStart?.let { balance.subtract(movementSince).negate().max(BigDecimal.ZERO) }
        val paidSince = sinceCycle.orEmpty()
            .filter { it.direction == TransactionDirection.CREDIT && it.kind in PAYMENT_KINDS }
            .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
        val spentThisCycle = LedgerMath.netSpendExact(
            sinceCycle ?: own.filter { onOrAfter(today.withDayOfMonth(1), it) },
        )

        val estimated = statementBalance == null
        // Never more than is actually owed now: a refund since the statement
        // lowers what's left to pay too.
        val amountDue = (statementBalance?.subtract(paidSince)?.max(BigDecimal.ZERO) ?: outstanding).min(outstanding)

        val dueDate = account.dueDayOfMonth?.let { day ->
            if (cycleStart != null) firstOnOrAfter(day, cycleStart) else firstOnOrAfter(day, today)
        }
        val daysUntilDue = dueDate?.let { ChronoUnit.DAYS.between(today, it) }

        val status = when {
            outstanding.signum() == 0 -> Status.NOTHING_OWED
            amountDue.signum() == 0 -> Status.PAID
            daysUntilDue == null -> Status.NO_DUE_DATE
            daysUntilDue < 0 -> Status.OVERDUE
            daysUntilDue <= DUE_SOON_DAYS -> Status.DUE_SOON
            else -> Status.DUE
        }

        return CardStatus(
            outstanding = outstanding,
            creditLimit = limit,
            available = available,
            utilisation = utilisation,
            cycleStart = cycleStart,
            statementBalance = statementBalance,
            paidSinceStatement = paidSince,
            amountDue = amountDue,
            dueDate = dueDate,
            daysUntilDue = daysUntilDue,
            spentThisCycle = spentThisCycle,
            status = status,
            estimated = estimated,
        )
    }

    /** [day] of [month], clamped to the month's length. */
    fun clampedDay(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /** The latest date on or before [today] whose day-of-month is [day] (clamped). */
    fun mostRecentDay(day: Int, today: LocalDate): LocalDate {
        val thisMonth = clampedDay(YearMonth.from(today), day)
        return if (!thisMonth.isAfter(today)) thisMonth else clampedDay(YearMonth.from(today).minusMonths(1), day)
    }

    /** The earliest date on or after [from] whose day-of-month is [day] (clamped). */
    fun firstOnOrAfter(day: Int, from: LocalDate): LocalDate {
        val thisMonth = clampedDay(YearMonth.from(from), day)
        return if (!thisMonth.isBefore(from)) thisMonth else clampedDay(YearMonth.from(from).plusMonths(1), day)
    }

    /** Kinds that pay a card down -- see [CardStatus.paidSinceStatement]. */
    val PAYMENT_KINDS = setOf(TransactionKind.CARD_PAYMENT, TransactionKind.TRANSFER)
}
