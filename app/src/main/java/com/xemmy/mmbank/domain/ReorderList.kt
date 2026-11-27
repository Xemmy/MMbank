package com.xemmy.mmbank.domain

/** Pure helpers for the one-step moves behind every reorderable list. */
object ReorderList {
    /** The ids in their new order after moving [id] one step up (-1) or down (+1); unchanged at the ends. */
    fun <T> move(orderedIds: List<T>, id: T, direction: Int): List<T> {
        val i = orderedIds.indexOf(id)
        val j = i + direction
        if (i < 0 || j < 0 || j >= orderedIds.size) return orderedIds
        return orderedIds.toMutableList().also { java.util.Collections.swap(it, i, j) }
    }

    /**
     * Moves [id] one step among [siblingIds] (in their shown order) but returns the FULL [allIds]
     * order: the siblings keep the slots they already occupy, only reshuffled between themselves, so
     * every other row stays exactly where it was. Unchanged if the move is not possible.
     */
    fun moveAmongSiblings(allIds: List<Long>, siblingIds: List<Long>, id: Long, direction: Int): List<Long> {
        val movedSiblings = move(siblingIds, id, direction)
        if (movedSiblings == siblingIds) return allIds
        val siblingSet = siblingIds.toSet()
        val iterator = movedSiblings.iterator()
        return allIds.map { if (it in siblingSet && iterator.hasNext()) iterator.next() else it }
    }
}
