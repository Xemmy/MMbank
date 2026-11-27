package com.xemmy.mmbank.domain.bills

import com.xemmy.mmbank.domain.intelligence.BillAdvancer
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** "Repeat" choice on the transaction page. A repeat is a reminder (a [Bill]); it never writes ledger rows. */
enum class Repeat(val label: String, val recurrence: BillRecurrence?) {
    NEVER("Never", null),
    WEEKLY("Weekly", BillRecurrence.WEEKLY),
    MONTHLY("Monthly", BillRecurrence.MONTHLY),
    YEARLY("Yearly", BillRecurrence.YEARLY),
    ;

    companion object {
        fun fromStored(value: String?): Repeat = entries.firstOrNull { it.name == value } ?: NEVER
    }
}

/** What "Log payment" on a bill hands to the transaction page, so a cash payment is one tap. */
data class BillPrefill(val amount: BigDecimal, val currency: String, val merchant: String)

object RepeatPlanner {

    /**
     * The reminder a repeating transaction creates, due one cycle after [date]; null for [Repeat.NEVER]
     * or a non-positive amount. The picked day is kept as the anchor so a 31st stays a 31st through
     * short months (see [BillAdvancer]).
     */
    fun billFor(
        repeat: Repeat,
        name: String?,
        fallbackName: String,
        amount: BigDecimal,
        currency: String,
        date: LocalDate,
        zone: ZoneId,
        createdAtMillis: Long,
    ): Bill? {
        val recurrence = repeat.recurrence ?: return null
        if (amount.signum() <= 0) return null
        val thisPayment = Bill(
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName,
            amount = amount,
            currencyCode = currency.trim().uppercase(),
            recurrence = recurrence,
            nextDueDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            createdAt = createdAtMillis,
            dueDayAnchor = if (recurrence.period.toTotalMonths() > 0) date.dayOfMonth else null,
        )
        // The payment being logged is this cycle; the reminder is for the next one.
        return BillAdvancer.advance(thisPayment, zone)
    }

    fun prefillFor(bill: Bill): BillPrefill = BillPrefill(bill.amount, bill.currencyCode, bill.name)
}
