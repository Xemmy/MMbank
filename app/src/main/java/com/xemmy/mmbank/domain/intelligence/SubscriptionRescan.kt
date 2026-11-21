package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * One full re-evaluation of the subscription table: given what is stored, the
 * user's links and the ledger, decides which rows to write and which to drop.
 * Pure, so every rule is covered by a JVM test.
 *
 * - **A charge linked to a row belongs only to that row.** A user-owned row
 *   (hand-added, edited, or with any linked charge) recomputes its count, last
 *   seen and next due from its own linked charges and keeps what was typed
 *   (name, amount, cadence). Several rows at one merchant, even with the same
 *   amount on the same card, therefore never merge.
 * - **Detection only sees unlinked charges**, and detected rows keep their id
 *   and dismissed flag when the radar finds them again, matched by brand key
 *   and currency so a changed bank spelling does not create a duplicate.
 * - **A detected row that is not found again, and was not dismissed, is
 *   removed**, so a cancelled subscription stops showing as "overdue" forever.
 */
object SubscriptionRescan {

    data class Result(
        /** Rows to insert (id 0) or replace (same id). */
        val upserts: List<Subscription>,
        val deleteIds: List<Long>,
        /** Links to add: an unambiguous new charge of a user-owned row (see [autoAttach]). */
        val newLinks: List<SubscriptionLink>,
        val created: Int,
        val updated: Int,
        val removed: Int,
    )

    /** Brand key when the normalizer has one, else the plain lower-cased name. */
    fun keyOf(merchant: String?): String =
        merchant?.let { MerchantNormalizer.key(it).ifEmpty { it.trim().lowercase() } }.orEmpty()

    /** A new charge may auto-join a user-owned row only within this fraction of its amount. */
    private const val ATTACH_TOLERANCE = 0.05

    fun plan(
        stored: List<Subscription>,
        links: List<SubscriptionLink>,
        ledger: List<Transaction>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val real = ledger.filter {
            it.kind == TransactionKind.SPEND && !it.excludeFromStats && it.direction == TransactionDirection.DEBIT
        }
        val byId = real.associateBy { it.id }
        val storedIds = stored.map { it.id }.toSet()
        // A link to a vanished row is ignored.
        val liveLinks = links.filter { it.subscriptionId in storedIds }
        val linkedByRow = liveLinks.groupBy { it.subscriptionId }
        val userOwned = stored.filter { it.isManual || linkedByRow.containsKey(it.id) }
        val userOwnedIds = userOwned.map { it.id }.toSet()
        val detectedStored = stored.filter { it.id !in userOwnedIds }

        // Unambiguous new charges join the one user-owned row they obviously belong to.
        val linkedTxIds = liveLinks.map { it.transactionId }.toMutableSet()
        val attached = autoAttach(userOwned, linkedByRow, byId, real.filter { it.id !in linkedTxIds })
        linkedTxIds += attached.map { it.transactionId }
        val allLinkedByRow = (liveLinks + attached).groupBy { it.subscriptionId }

        val upserts = mutableListOf<Subscription>()
        var updated = 0

        // User-owned rows follow their own charges.
        for (row in userOwned) {
            val charges = allLinkedByRow[row.id].orEmpty()
                .mapNotNull { byId[it.transactionId] }
                .sortedBy { it.timestampMillis }
            val next = recompute(row, charges, nowMillis, zone).copy(isManual = true, merchantKey = keyOf(row.merchant))
            if (next != row) {
                upserts += next
                updated++
            }
        }

        // Detection on what no user-owned row claims, minus anything a user-owned row already covers.
        val pool = real.filter { it.id !in linkedTxIds }
        val scan = SubscriptionRadarEngine.scan(pool, zone)
        val detected = (scan.active + scan.possible).map { it.subscription }
            .filterNot { d -> userOwned.any { covers(it, d) } }
        val available = detectedStored.toMutableList()
        val keep = mutableSetOf<Long>()
        var created = 0
        for (d in detected) {
            val key = keyOf(d.merchant)
            val match = available
                .filter { keyOf(it.merchant) == key && it.currencyCode.equals(d.currencyCode, ignoreCase = true) }
                .minByOrNull { (it.averageAmount - d.averageAmount).abs() }
            if (match == null) {
                upserts += d.copy(merchantKey = key)
                created++
            } else {
                available.remove(match)
                keep += match.id
                val next = d.copy(id = match.id, dismissed = match.dismissed, merchantKey = key)
                if (next != match) {
                    upserts += next
                    updated++
                }
            }
        }
        // Stored detected rows not found again: dismissed ones stay (so they are not re-detected as new).
        val gone = detectedStored.filter { it.id !in keep && !it.dismissed }
        return Result(upserts, gone.map { it.id }, attached, created, updated, gone.size)
    }

    private fun covers(owned: Subscription, detected: Subscription): Boolean =
        keyOf(owned.merchant) == keyOf(detected.merchant) &&
            owned.currencyCode.equals(detected.currencyCode, ignoreCase = true) &&
            within(detected.averageAmount, owned.averageAmount)

    private fun within(amount: BigDecimal, reference: BigDecimal): Boolean {
        val ref = reference.toDouble()
        return ref > 0.0 && abs(amount.toDouble() - ref) / ref <= ATTACH_TOLERANCE
    }

    /**
     * A charge newer than everything the row already owns, with the same brand
     * key, currency and (within 5%) amount, joins the row when it is the only
     * user-owned row that fits. With identical twins the match is ambiguous, so
     * the charge stays unlinked for the user to place. "Newer" matters: a charge
     * the user detached by hand is older than the row's latest and stays out.
     */
    private fun autoAttach(
        userOwned: List<Subscription>,
        linkedByRow: Map<Long, List<SubscriptionLink>>,
        byId: Map<Long, Transaction>,
        unlinked: List<Transaction>,
    ): List<SubscriptionLink> {
        if (userOwned.isEmpty()) return emptyList()
        val floor = userOwned.associate { row ->
            val newest = linkedByRow[row.id].orEmpty()
                .mapNotNull { byId[it.transactionId]?.timestampMillis }
                .maxOrNull() ?: 0L
            row.id to maxOf(newest, row.lastSeenMillis)
        }
        return unlinked.mapNotNull { tx ->
            val fits = userOwned.filter { row ->
                keyOf(row.merchant) == keyOf(tx.merchant) &&
                    row.currencyCode.equals(tx.currencyCode, ignoreCase = true) &&
                    within(tx.amount, row.averageAmount) &&
                    tx.timestampMillis > floor.getValue(row.id)
            }
            fits.singleOrNull()?.let { SubscriptionLink(tx.id, it.id) }
        }
    }

    /** Count, last seen and next due from the row's own charges; with none, the typed date rolls forward once past. */
    private fun recompute(row: Subscription, charges: List<Transaction>, nowMillis: Long, zone: ZoneId): Subscription {
        if (charges.isEmpty()) {
            return row.copy(nextDueDateMillis = rollForward(row.nextDueDateMillis, row.cadence, nowMillis, zone))
        }
        return row.copy(
            occurrenceCount = charges.size,
            lastSeenMillis = charges.last().timestampMillis,
            nextDueDateMillis = SubscriptionRadarEngine.nextDue(charges, row.cadence, zone),
        )
    }

    internal fun rollForward(dueMillis: Long, cadence: SubscriptionCadence, nowMillis: Long, zone: ZoneId): Long {
        if (dueMillis >= nowMillis || dueMillis <= 0L) return dueMillis
        var due = Instant.ofEpochMilli(dueMillis).atZone(zone)
        var steps = 0
        while (due.toInstant().toEpochMilli() < nowMillis && steps < 1000) {
            due = cadence.months?.let { due.plusMonths(it.toLong()) } ?: due.plusDays(cadence.nominalDays)
            steps++
        }
        return due.toInstant().toEpochMilli()
    }
}
