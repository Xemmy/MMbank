package com.xemmy.mmbank.domain.people

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonBackfillTest {

    private fun tx(id: Long, person: String?, kind: TransactionKind = TransactionKind.SPEND) = Transaction(
        id = id, amount = BigDecimal.TEN, currencyCode = "AED", direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS, timestampMillis = id, person = person, kind = kind,
    )

    @Test
    fun `rows with a missing or blank person are selected`() {
        val ids = PersonBackfill.idsWithoutPerson(
            listOf(tx(1, null), tx(2, ""), tx(3, "   "), tx(4, "Sara"), tx(5, " Omar ")),
        )
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `balance adjustments are never given a person`() {
        val ids = PersonBackfill.idsWithoutPerson(listOf(tx(1, null, TransactionKind.ADJUSTMENT), tx(2, null)))
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `nothing is selected when everyone is assigned`() {
        assertTrue(PersonBackfill.idsWithoutPerson(listOf(tx(1, "Sara"))).isEmpty())
    }

    @Test
    fun `the summary counts in plain words`() {
        assertEquals("1 transaction has no person", PersonBackfill.summary(1))
        assertEquals("212 transactions have no person", PersonBackfill.summary(212))
    }

    @Test
    fun `the default person is preselected whatever its casing`() {
        assertEquals("Sara", PersonBackfill.preselect(listOf("Omar", "Sara"), " sara "))
    }

    @Test
    fun `a single choice is preselected when there is no default`() {
        assertEquals("Omar", PersonBackfill.preselect(listOf("Omar"), null))
    }

    @Test
    fun `nothing is preselected when the default is not on offer and there are several`() {
        assertNull(PersonBackfill.preselect(listOf("Omar", "Sara"), "Layla"))
        assertNull(PersonBackfill.preselect(emptyList(), "Layla"))
    }
}
