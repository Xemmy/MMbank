package com.xemmy.mmbank.domain.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonCountsTest {

    @Test
    fun `names differing only by case are one person`() {
        val counts = PersonCounts.countByName(listOf("John", "john", "JOHN"))
        assertEquals(1, counts.size)
        assertEquals(3, PersonCounts.countFor(counts, "John"))
    }

    @Test
    fun `surrounding whitespace does not split a person`() {
        val counts = PersonCounts.countByName(listOf("  John ", "John", "John  "))
        assertEquals(1, counts.size)
        assertEquals(3, PersonCounts.countFor(counts, "John"))
    }

    @Test
    fun `nulls and blanks are not counted as people`() {
        val counts = PersonCounts.countByName(listOf(null, "", "   ", "John"))
        assertEquals(1, counts.size)
        assertEquals(1, PersonCounts.countFor(counts, "John"))
    }

    @Test
    fun `distinct people are counted separately`() {
        val counts = PersonCounts.countByName(listOf("John", "Jane", "John"))
        assertEquals(2, counts.size)
        assertEquals(2, PersonCounts.countFor(counts, "John"))
        assertEquals(1, PersonCounts.countFor(counts, "Jane"))
    }

    @Test
    fun `lookup matches regardless of how the caller spells the name`() {
        val counts = PersonCounts.countByName(listOf("John"))
        assertEquals(1, PersonCounts.countFor(counts, "JOHN"))
        assertEquals(1, PersonCounts.countFor(counts, " john "))
    }

    @Test
    fun `an unknown name counts zero rather than throwing`() {
        val counts = PersonCounts.countByName(listOf("John"))
        assertEquals(0, PersonCounts.countFor(counts, "Nobody"))
        assertEquals(0, PersonCounts.countFor(emptyMap(), "John"))
    }

    @Test
    fun `no people yields no counts`() {
        assertTrue(PersonCounts.countByName(emptyList()).isEmpty())
        assertTrue(PersonCounts.countByName(listOf(null, "  ")).isEmpty())
    }

    @Test
    fun `key is the shared normalization rule`() {
        assertEquals(PersonCounts.key("John"), PersonCounts.key("  jOHn "))
    }

    // --- reassignTargets: who a person delete is allowed to hand transactions to ---

    @Test
    fun `reassignTargets merges listed and unlisted names`() {
        val targets = PersonCounts.reassignTargets(listed = listOf("Jane"), unlisted = listOf("Bob"), excluding = "John")
        assertEquals(listOf("Bob", "Jane"), targets)
    }

    @Test
    fun `reassignTargets dedupes a name present in both lists regardless of casing`() {
        val targets = PersonCounts.reassignTargets(listed = listOf("Jane"), unlisted = listOf("jane"), excluding = "John")
        assertEquals(listOf("Jane"), targets)
    }

    @Test
    fun `reassignTargets excludes the deleted person regardless of casing`() {
        val targets = PersonCounts.reassignTargets(listed = listOf("JOHN", "Jane"), unlisted = emptyList(), excluding = "john")
        assertEquals(listOf("Jane"), targets)
    }

    @Test
    fun `reassignTargets sorts the result`() {
        val targets = PersonCounts.reassignTargets(listed = listOf("Zoe"), unlisted = listOf("Amy"), excluding = "John")
        assertEquals(listOf("Amy", "Zoe"), targets)
    }

    @Test
    fun `reassignTargets is empty when both inputs are empty`() {
        assertTrue(PersonCounts.reassignTargets(listed = emptyList(), unlisted = emptyList(), excluding = "John").isEmpty())
    }
}
