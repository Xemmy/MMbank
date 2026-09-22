package com.xemmy.mmbank.domain.parser

/**
 * Which still-unassigned transactions a freshly learned merchant -> account
 * rule should be applied to.
 *
 * Pure, so the "which rows does a correction reach backwards into" decision is
 * testable without Room — the same reason [CaptureAccountResolver] and
 * [CaptureCategoryResolver] are pure. The caller supplies only rows that are
 * already `accountId IS NULL`, so this never has to decide whether to overwrite
 * an assignment the user made by hand: it can't.
 */
object MerchantAccountLearner {

    /** An unassigned transaction, reduced to what the decision needs. */
    data class Candidate(val id: Long, val merchant: String)

    /**
     * Ids from [candidates] whose merchant is the same merchant as [merchant] —
     * exact case-insensitive match, or the same [MerchantNormalizer] key.
     *
     * A blank or unkeyable [merchant] reaches nothing beyond an exact match:
     * two merchants that both key to `""` are not the same merchant, and a
     * wrong account here silently moves money between balances.
     */
    fun idsToBackfill(candidates: List<Candidate>, merchant: String): List<Long> {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = MerchantNormalizer.key(trimmed)

        return candidates.filter { candidate ->
            candidate.merchant.trim().equals(trimmed, ignoreCase = true) ||
                (key.isNotEmpty() && MerchantNormalizer.key(candidate.merchant) == key)
        }.map { it.id }
    }
}
