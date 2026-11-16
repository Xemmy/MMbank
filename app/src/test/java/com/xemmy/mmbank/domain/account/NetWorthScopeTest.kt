package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetWorthScopeTest {
    private fun a(id: Long, excluded: Boolean) =
        Account(id = id, name = "A$id", type = AccountType.CHECKING, currencyCode = "AED", excludeFromTotals = excluded)

    @Test
    fun `every account counts unless switched off`() {
        assertTrue(NetWorthScope.counts(a(1, false)))
        assertFalse(NetWorthScope.counts(a(2, true)))
    }

    @Test
    fun `filter keeps order and drops only excluded accounts`() {
        val all = listOf(a(1, false), a(2, true), a(3, false))
        assertEquals(listOf(1L, 3L), NetWorthScope.filter(all).map { it.id })
        assertTrue(NetWorthScope.anyExcluded(all))
        assertFalse(NetWorthScope.anyExcluded(listOf(a(1, false))))
    }

    @Test
    fun `an excluded account keeps its own balance fields untouched`() {
        val excluded = a(2, true).copy(openingBalance = java.math.BigDecimal("500"))
        assertEquals(java.math.BigDecimal("500"), excluded.openingBalance)
    }
}
