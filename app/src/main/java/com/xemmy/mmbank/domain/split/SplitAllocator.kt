package com.xemmy.mmbank.domain.split

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Divides a [GroupExpense][com.xemmy.mmbank.domain.model.GroupExpense]'s
 * amount among its participants under one of four split types. Generic over
 * the participant identifier ([T]) rather than hard-coded to a nullable
 * `Long` (the app's own "null = me" convention) so it's exercised with plain
 * values under test; callers pass `Long?` in practice.
 *
 * Every function that can succeed or fail on bad input (amounts that don't
 * sum to the total, percentages that don't sum to 100, adjustments that
 * don't net to zero) returns null on failure rather than throwing -- the
 * caller is a form the user is actively filling in, where "not valid yet" is
 * routine, not exceptional.
 */
object SplitAllocator {

    private val CENT = BigDecimal("0.01")

    /**
     * Equal shares, each rounded down to the cent, with the leftover cents
     * from that rounding (there's always at most [participantIds].size - 1 of
     * them) handed one at a time to the first participants in iteration
     * order -- so the shares always sum back to exactly [totalAmount], never
     * a cent under or over.
     */
    fun <T> splitEqually(totalAmount: BigDecimal, participantIds: List<T>): Map<T, BigDecimal> {
        require(participantIds.isNotEmpty()) { "at least one participant is required" }
        val count = participantIds.size
        val base = totalAmount.divide(BigDecimal(count), 2, RoundingMode.DOWN)
        var remainder = totalAmount - (base * BigDecimal(count))
        val result = LinkedHashMap<T, BigDecimal>(count)
        for (id in participantIds) {
            if (remainder.signum() > 0) {
                result[id] = base + CENT
                remainder -= CENT
            } else {
                result[id] = base
            }
        }
        return result
    }

    /** Exact per-participant amounts, accepted only if they already sum to [totalAmount]. */
    fun <T> splitByExactAmounts(totalAmount: BigDecimal, amounts: Map<T, BigDecimal>): Map<T, BigDecimal>? {
        if (amounts.isEmpty()) return null
        val sum = amounts.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        return if (sum.compareTo(totalAmount) == 0) amounts else null
    }

    /**
     * Percentage shares (0-100 each), accepted only if they sum to 100
     * within a hundredth of a percent. Every share but the last is rounded to
     * the cent; the last absorbs whatever's left so the total always lands
     * on exactly [totalAmount] instead of drifting from independent rounding.
     */
    fun <T> splitByPercentages(totalAmount: BigDecimal, percentages: Map<T, Double>): Map<T, BigDecimal>? {
        if (percentages.isEmpty()) return null
        val totalPercent = percentages.values.sum()
        if (kotlin.math.abs(totalPercent - 100.0) > 0.01) return null

        val entries = percentages.entries.toList()
        val result = LinkedHashMap<T, BigDecimal>(entries.size)
        var allocated = BigDecimal.ZERO
        entries.forEachIndexed { index, (id, percent) ->
            val share = if (index == entries.lastIndex) {
                totalAmount - allocated
            } else {
                (totalAmount * BigDecimal(percent)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            }
            result[id] = share
            allocated += share
        }
        return result
    }

    /**
     * An equal split (see [splitEqually]) shifted by a per-participant
     * adjustment, accepted only if the adjustments net to zero -- one
     * participant paying more only makes sense paired with others paying
     * less by the same total, so the split still sums to [totalAmount].
     * A participant missing from [adjustments] gets no adjustment (zero).
     */
    fun <T> splitByAdjustments(
        totalAmount: BigDecimal,
        participantIds: List<T>,
        adjustments: Map<T, BigDecimal>,
    ): Map<T, BigDecimal>? {
        require(participantIds.isNotEmpty()) { "at least one participant is required" }
        val adjustmentSum = participantIds.fold(BigDecimal.ZERO) { acc, id -> acc + (adjustments[id] ?: BigDecimal.ZERO) }
        if (adjustmentSum.signum() != 0) return null

        val equalShares = splitEqually(totalAmount, participantIds)
        return participantIds.associateWith { id -> equalShares.getValue(id) + (adjustments[id] ?: BigDecimal.ZERO) }
    }

    /**
     * Inverse of [splitByPercentages]: recovers the percentage each
     * participant's already-computed [amounts] represents, for pre-filling
     * an edit form from a previously saved split. Zero when [totalAmount]
     * isn't positive rather than dividing by it.
     */
    fun <T> derivePercentages(totalAmount: BigDecimal, amounts: Map<T, BigDecimal>): Map<T, Double> {
        if (totalAmount.signum() <= 0) return amounts.mapValues { 0.0 }
        return amounts.mapValues { (_, amount) ->
            amount.divide(totalAmount, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
        }
    }

    /**
     * Inverse of [splitByAdjustments]: recovers the per-participant
     * adjustment that would turn an equal split of [totalAmount] into
     * [amounts], for pre-filling an edit form from a previously saved split.
     * A participant missing from [amounts] gets no adjustment (zero).
     */
    fun <T> deriveAdjustments(totalAmount: BigDecimal, participantIds: List<T>, amounts: Map<T, BigDecimal>): Map<T, BigDecimal> {
        val equalShares = splitEqually(totalAmount, participantIds)
        return participantIds.associateWith { id -> (amounts[id] ?: BigDecimal.ZERO) - equalShares.getValue(id) }
    }
}
