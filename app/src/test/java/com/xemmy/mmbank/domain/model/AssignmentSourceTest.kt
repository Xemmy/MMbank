package com.xemmy.mmbank.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentSourceTest {

    @Test
    fun `only the app's own guesses may be re-assigned, never the user's choice`() {
        assertTrue(AssignmentSource.KEYWORD.isReassignable)
        assertTrue(AssignmentSource.LEARNED.isReassignable)
        assertTrue(AssignmentSource.AI.isReassignable)
        assertFalse(AssignmentSource.USER.isReassignable)
        assertFalse(AssignmentSource.DEFAULT.isReassignable)
    }

    @Test
    fun `each source explains itself in plain words`() {
        assertEquals("Chosen by you", AssignmentSource.USER.explain("category"))
        assertEquals("Learned from your earlier choices", AssignmentSource.LEARNED.explain("person"))
        assertEquals("Guessed from the merchant name", AssignmentSource.KEYWORD.explain("category"))
        assertEquals("Suggested by AI", AssignmentSource.AI.explain("category"))
        assertEquals("No person was chosen automatically", AssignmentSource.DEFAULT.explain("person"))
    }

    @Test
    fun `a transaction defaults both sources to DEFAULT`() {
        val tx = Transaction(
            amount = java.math.BigDecimal.ONE, currencyCode = "AED", direction = TransactionDirection.DEBIT,
            source = TransactionSource.SMS, timestampMillis = 0L,
        )
        assertEquals(AssignmentSource.DEFAULT, tx.categorySource)
        assertEquals(AssignmentSource.DEFAULT, tx.personSource)
    }

    @Test
    fun `afterEdit keeps the source when nothing changed`() {
        assertEquals(AssignmentSource.LEARNED, AssignmentSource.afterEdit(AssignmentSource.LEARNED, 5L, 5L))
        assertEquals(AssignmentSource.KEYWORD, AssignmentSource.afterEdit(AssignmentSource.KEYWORD, "Sam", "Sam"))
        assertEquals(AssignmentSource.DEFAULT, AssignmentSource.afterEdit(AssignmentSource.DEFAULT, null as Long?, null))
    }

    @Test
    fun `afterEdit makes a changed value the user's and a cleared one the default`() {
        assertEquals(AssignmentSource.USER, AssignmentSource.afterEdit(AssignmentSource.KEYWORD, 5L, 6L))
        assertEquals(AssignmentSource.USER, AssignmentSource.afterEdit(AssignmentSource.DEFAULT, null, "Sam"))
        assertEquals(AssignmentSource.DEFAULT, AssignmentSource.afterEdit(AssignmentSource.USER, 5L, null))
    }
}
