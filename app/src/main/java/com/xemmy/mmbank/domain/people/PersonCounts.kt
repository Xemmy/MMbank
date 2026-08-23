package com.xemmy.mmbank.domain.people

/**
 * How many transactions carry each person name.
 *
 * People names are free text typed on transactions, so "John", "john" and
 * " John " are the same person to everyone except SQLite's default `=`
 * comparison. This object owns the one normalization rule -- trim, then
 * lowercase -- so the count shown in the People screen's delete prompt and the
 * `COLLATE NOCASE` UPDATE that actually moves those rows agree on which rows
 * they mean. Pure and top-level for JVM testing.
 */
object PersonCounts {

    /** Normalized lookup key for [name]; the single source of truth for name identity. */
    fun key(name: String): String = name.trim().lowercase()

    /**
     * Counts [persons] by normalized name, skipping nulls and blanks. The keys
     * are normalized, so look results up with [countFor] rather than indexing
     * the map with a raw name.
     */
    fun countByName(persons: List<String?>): Map<String, Int> =
        persons.mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .groupingBy { key(it) }
            .eachCount()

    /** How many transactions carry [name], in any casing. Unknown names count zero. */
    fun countFor(counts: Map<String, Int>, name: String): Int = counts[key(name)] ?: 0
}
