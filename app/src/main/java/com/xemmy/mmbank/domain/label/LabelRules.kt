package com.xemmy.mmbank.domain.label

import com.xemmy.mmbank.domain.model.Label

/** Which labels a transaction carries: one (transaction, label) pair. */
data class LabelLink(val transactionId: Long, val labelId: Long)

/** Naming, colour and merge rules for labels, kept pure so they are testable on the JVM. */
object LabelRules {
    const val MAX_NAME_LENGTH = 30

    /** Colours handed out in turn to new labels. */
    val PALETTE = listOf("#56B4E9", "#E69F00", "#2FC49A", "#D98CB8", "#6F9CF5", "#F07C3A", "#F0E442", "#A9B0BD")

    /** Trimmed, inner whitespace collapsed, capped at [MAX_NAME_LENGTH]; blank gives null. */
    fun normalize(name: String): String? =
        name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** True when another label already has this name, ignoring case (the label being renamed is [ignoreId]). */
    fun isDuplicate(existing: List<Label>, name: String, ignoreId: Long? = null): Boolean {
        val n = normalize(name) ?: return false
        return existing.any { it.id != ignoreId && it.name.equals(n, ignoreCase = true) }
    }

    /** The next colour in turn, given how many labels exist. */
    fun nextColor(existingCount: Int): String = PALETTE[existingCount % PALETTE.size]

    /**
     * Links to add so every transaction labelled [from] also carries [to]; a transaction that
     * already has both adds nothing. Removing [from] afterwards drops its own links.
     */
    fun mergeLinksToAdd(links: List<LabelLink>, from: Long, to: Long): List<LabelLink> {
        if (from == to) return emptyList()
        val alreadyTo = links.filter { it.labelId == to }.map { it.transactionId }.toSet()
        return links.filter { it.labelId == from && it.transactionId !in alreadyTo }
            .map { LabelLink(it.transactionId, to) }
            .distinct()
    }

    /** Label ids per transaction id. */
    fun byTransaction(links: List<LabelLink>): Map<Long, Set<Long>> =
        links.groupBy({ it.transactionId }, { it.labelId }).mapValues { it.value.toSet() }

    /** How many transactions use each label. */
    fun useCounts(links: List<LabelLink>): Map<Long, Int> = links.groupingBy { it.labelId }.eachCount()
}
