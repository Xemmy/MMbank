package com.xemmy.mmbank.domain.filter

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The drill-down category filter. Kept apart from TransactionFilterEngineTest, which also needs UI helpers. */
class TransactionFilterEngineCategoryTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 7)

    private fun tx(id: Long, categoryId: Long?) = Transaction(
        id = id,
        amount = BigDecimal.TEN,
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        categoryId = categoryId,
    )

    private val rows = listOf(tx(1, 2), tx(2, 5), tx(3, 3), tx(4, null))

    @Test
    fun `keeps only rows in the chosen categories`() {
        val out = TransactionFilterEngine.filter(rows, ActivityFilters(categoryIds = setOf(2, 5)), zone = zone, today = today)
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }

    @Test
    fun `null means any category, including uncategorized rows`() {
        assertEquals(4, TransactionFilterEngine.filter(rows, ActivityFilters(), zone = zone, today = today).size)
    }

    @Test
    fun `counts toward the More filters badge`() {
        assertEquals(0, TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters()))
        assertEquals(1, TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters(categoryIds = setOf(2))))
    }
}
