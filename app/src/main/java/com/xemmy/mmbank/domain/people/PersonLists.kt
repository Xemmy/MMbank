package com.xemmy.mmbank.domain.people

/**
 * Which people are offered where.
 *
 * The saved People list grows over the years: a former flatmate, a relative
 * who visited once. Their old transactions must keep their name and stay
 * findable, but they should not crowd the menu every time a new transaction is
 * assigned. So a person can be marked **inactive**:
 *
 *  - [assignable] -- the menus that *set* a person -- lists active people only;
 *  - [filterable] -- the Activity filter -- still lists everyone.
 *
 * Names compare by [PersonCounts.key] (trimmed, case-insensitive), the same
 * identity rule the rest of the People code uses. Pure, for JVM testing.
 */
object PersonLists {

    private fun clean(names: Iterable<String?>): List<String> =
        names.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { PersonCounts.key(it) }

    fun isInactive(name: String, inactive: Collection<String>): Boolean {
        val key = PersonCounts.key(name)
        return inactive.any { PersonCounts.key(it) == key }
    }

    /**
     * Everyone who can be picked when assigning: the saved people that are not
     * [inactive], plus [defaultPerson] -- which is always offered, since every
     * new capture is given to it anyway. Sorted.
     */
    fun assignable(saved: List<String>, inactive: Collection<String>, defaultPerson: String?): List<String> =
        clean(saved.filterNot { isInactive(it, inactive) } + defaultPerson).sorted()

    /**
     * [assignable] for one particular row: if the row already carries a person
     * who is not offered (inactive, or no longer saved), that name is added so
     * opening the menu shows the current value and never drops it.
     */
    fun withCurrent(assignable: List<String>, current: String?): List<String> =
        clean(assignable + current).let { all -> if (all.size == assignable.size) assignable else all.sorted() }

    /** Everyone, active or not, for filtering. Sorted. */
    fun filterable(saved: List<String>, defaultPerson: String?): List<String> =
        clean(saved + defaultPerson).sorted()

    /** [saved] with active people first and inactive ones after, each group in saved order. */
    fun activeFirst(saved: List<String>, inactive: Collection<String>): List<String> =
        saved.filterNot { isInactive(it, inactive) } + saved.filter { isInactive(it, inactive) }

    /**
     * The inactive list after marking [name] active or inactive. Names no
     * longer in [saved] are dropped along the way, so the list cannot fill up
     * with people who were deleted.
     */
    fun setActive(inactive: Collection<String>, saved: List<String>, name: String, active: Boolean): List<String> {
        val kept = clean(inactive).filter { it -> saved.any { s -> PersonCounts.key(s) == PersonCounts.key(it) } }
            .filterNot { PersonCounts.key(it) == PersonCounts.key(name) }
        return if (active) kept else clean(kept + name)
    }

    /** The inactive list after [from] was renamed to [to]: the status follows the person. */
    fun renamed(inactive: Collection<String>, from: String, to: String): List<String> =
        clean(inactive.map { if (PersonCounts.key(it) == PersonCounts.key(from)) to else it })
}
