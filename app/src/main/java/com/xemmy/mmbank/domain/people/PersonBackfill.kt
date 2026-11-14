package com.xemmy.mmbank.domain.people

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind

/**
 * "212 transactions have no person" -- which rows a one-tap fill would give a
 * person to. Pure, so the selection is unit tested.
 *
 * A row counts when its person is missing or blank. Balance adjustments are
 * left out: they are bookkeeping, not anybody's spending, and giving them a
 * person would put a correction into that person's totals.
 */
object PersonBackfill {

    fun idsWithoutPerson(transactions: List<Transaction>): List<Long> =
        transactions
            .filter { it.person.isNullOrBlank() && it.kind != TransactionKind.ADJUSTMENT }
            .map { it.id }

    /** "1 transaction has no person" / "212 transactions have no person". */
    fun summary(count: Int): String =
        if (count == 1) "1 transaction has no person" else "$count transactions have no person"

    /** The name to preselect: the default person when it is on offer, else the only choice, else none. */
    fun preselect(choices: List<String>, defaultPerson: String?): String? {
        val default = defaultPerson?.trim()?.takeIf { it.isNotEmpty() }
        return choices.firstOrNull { default != null && PersonCounts.key(it) == PersonCounts.key(default) }
            ?: choices.singleOrNull()
    }
}
