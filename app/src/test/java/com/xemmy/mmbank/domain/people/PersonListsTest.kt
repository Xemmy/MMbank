package com.xemmy.mmbank.domain.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonListsTest {

    private val saved = listOf("Sara", "Omar", "Layla", "Old Flatmate")

    @Test
    fun `assigning offers active people only, sorted`() {
        assertEquals(
            listOf("Layla", "Omar", "Sara"),
            PersonLists.assignable(saved, inactive = listOf("Old Flatmate"), defaultPerson = null),
        )
    }

    @Test
    fun `inactive names match whatever their casing or spacing`() {
        assertEquals(
            listOf("Layla", "Sara"),
            PersonLists.assignable(saved, inactive = listOf(" omar ", "OLD FLATMATE"), defaultPerson = null),
        )
    }

    @Test
    fun `the default person is always offered, even when not saved or marked inactive`() {
        assertEquals(listOf("Me", "Sara"), PersonLists.assignable(listOf("Sara"), emptyList(), "Me"))
        assertEquals(listOf("Omar", "Sara"), PersonLists.assignable(listOf("Sara", "Omar"), listOf("Omar"), "Omar"))
    }

    @Test
    fun `the default person is not listed twice`() {
        assertEquals(listOf("Sara"), PersonLists.assignable(listOf("Sara"), emptyList(), " sara "))
    }

    @Test
    fun `with nobody inactive everyone is offered`() {
        assertEquals(saved.sorted(), PersonLists.assignable(saved, emptyList(), null))
    }

    @Test
    fun `a row that carries an inactive person still shows that name in its own menu`() {
        val assignable = PersonLists.assignable(saved, listOf("Old Flatmate"), null)
        assertEquals(
            listOf("Layla", "Old Flatmate", "Omar", "Sara"),
            PersonLists.withCurrent(assignable, "Old Flatmate"),
        )
    }

    @Test
    fun `a row whose person is already offered, or has none, gets the list unchanged`() {
        val assignable = PersonLists.assignable(saved, listOf("Old Flatmate"), null)
        assertSame(assignable, PersonLists.withCurrent(assignable, "sara"))
        assertSame(assignable, PersonLists.withCurrent(assignable, null))
        assertSame(assignable, PersonLists.withCurrent(assignable, "  "))
    }

    @Test
    fun `filtering still lists everyone`() {
        assertEquals(
            listOf("Layla", "Me", "Old Flatmate", "Omar", "Sara"),
            PersonLists.filterable(saved, "Me"),
        )
    }

    @Test
    fun `active people come first, each group in saved order`() {
        assertEquals(
            listOf("Sara", "Layla", "Omar", "Old Flatmate"),
            PersonLists.activeFirst(saved, listOf("Old Flatmate", "Omar")),
        )
    }

    @Test
    fun `marking someone inactive and active again`() {
        val off = PersonLists.setActive(emptyList(), saved, "Omar", active = false)
        assertEquals(listOf("Omar"), off)
        assertTrue(PersonLists.isInactive("omar", off))

        val on = PersonLists.setActive(off, saved, "OMAR", active = true)
        assertTrue(on.isEmpty())
        assertFalse(PersonLists.isInactive("Omar", on))
    }

    @Test
    fun `marking someone inactive twice keeps one entry`() {
        val once = PersonLists.setActive(emptyList(), saved, "Omar", active = false)
        assertEquals(listOf("Omar"), PersonLists.setActive(once, saved, " omar", active = false))
    }

    @Test
    fun `people who were deleted drop out of the inactive list`() {
        val out = PersonLists.setActive(listOf("Ghost", "Omar"), saved, "Layla", active = false)
        assertEquals(listOf("Omar", "Layla"), out)
    }

    @Test
    fun `a renamed person keeps their inactive status`() {
        assertEquals(listOf("Flatmate", "Omar"), PersonLists.renamed(listOf("Old Flatmate", "Omar"), "old flatmate", "Flatmate"))
    }

    @Test
    fun `renaming into a name that is already inactive leaves one entry`() {
        assertEquals(1, PersonLists.renamed(listOf("Old Flatmate", "Omar"), "Old Flatmate", "omar").size)
    }
}
