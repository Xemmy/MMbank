package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/** Pure rules behind the add/edit subscription dialog. */
object SubscriptionForm {

    /** A form that passed [validate]. */
    data class Parsed(val name: String, val amount: BigDecimal, val currency: String)

    /** Null while the name is blank, the amount is not a positive number, or the currency is not 3 letters. */
    fun validate(name: String, amount: String, currency: String): Parsed? {
        val cleanName = name.trim()
        val value = amount.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val code = currency.trim().uppercase()
        if (cleanName.isEmpty() || value == null || code.length != 3 || !code.all { it.isLetter() }) return null
        return Parsed(cleanName, value.setScale(2, RoundingMode.HALF_UP), code)
    }

    /**
     * The row to save: [initial] with the typed fields replaced (so its id, counts and
     * last seen survive an edit), or a fresh one when [initial] is null. Either way it is user-owned.
     */
    fun build(
        initial: Subscription?,
        parsed: Parsed,
        cadence: SubscriptionCadence,
        nextDueMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Subscription {
        val base = initial ?: Subscription(
            merchant = parsed.name,
            averageAmount = parsed.amount,
            currencyCode = parsed.currency,
            cadence = cadence,
            nextDueDateMillis = nextDueMillis,
            lastSeenMillis = 0L,
            occurrenceCount = 0,
        )
        return base.copy(
            merchant = parsed.name,
            averageAmount = parsed.amount,
            currencyCode = parsed.currency,
            cadence = cadence,
            nextDueDateMillis = nextDueMillis,
            previousAmount = if (initial != null && initial.averageAmount.compareTo(parsed.amount) != 0) null else base.previousAmount,
            dismissed = false,
            isManual = true,
            confidence = 1.0,
            merchantKey = SubscriptionRescan.keyOf(parsed.name),
            anchorDay = anchorDayOf(nextDueMillis, cadence, zone),
        )
    }

    /** The 29-31 day-of-month a month-based due date sits on, else 0 (a short month must not drag it to the 28th). */
    fun anchorDayOf(dueMillis: Long, cadence: SubscriptionCadence, zone: ZoneId): Int {
        if (cadence.months == null || dueMillis <= 0L) return 0
        val day = Instant.ofEpochMilli(dueMillis).atZone(zone).dayOfMonth
        return if (day >= 29) day else 0
    }
}

/** A removed subscription row plus its links, enough to put it back exactly (Undo). */
data class SubscriptionSnapshot(val row: Subscription, val links: List<SubscriptionLink>) {
    companion object {
        /** Keeps only the links that point at [row]. */
        fun of(row: Subscription, allLinks: List<SubscriptionLink>) =
            SubscriptionSnapshot(row, allLinks.filter { it.subscriptionId == row.id })
    }
}
