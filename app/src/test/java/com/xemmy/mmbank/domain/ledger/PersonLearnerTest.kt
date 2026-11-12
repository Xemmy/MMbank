package com.xemmy.mmbank.domain.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonLearnerTest {

    @Test
    fun `no history means the default person`() {
        assertEquals("Me", PersonLearner.pick(emptyList(), "Me"))
    }

    @Test
    fun `one earlier row is not yet a pattern`() {
        assertEquals("Me", PersonLearner.pick(listOf("Sara"), "Me"))
    }

    @Test
    fun `the last two rows going to the same person is a pattern`() {
        assertEquals("Sara", PersonLearner.pick(listOf("Sara", "Sara"), "Me"))
    }

    @Test
    fun `a pattern is matched ignoring case and keeps the newest spelling`() {
        assertEquals("sara", PersonLearner.pick(listOf("sara", "Sara"), "Me"))
    }

    @Test
    fun `two different recent persons is no pattern`() {
        assertEquals("Me", PersonLearner.pick(listOf("Sara", "Omar"), "Me"))
    }

    @Test
    fun `two defaults in a row hand the merchant back`() {
        assertEquals("Me", PersonLearner.pick(listOf("Me", "Me"), "Me"))
    }

    @Test
    fun `only the two most recent rows count`() {
        assertEquals("Me", PersonLearner.pick(listOf("Me", "Sara", "Sara"), "Me"))
    }

    @Test
    fun `blank entries are ignored and a blank default is no person`() {
        assertEquals("Me", PersonLearner.pick(listOf("  ", ""), "Me"))
        assertNull(PersonLearner.pick(emptyList(), "  "))
        assertNull(PersonLearner.pick(emptyList(), null))
    }
}
