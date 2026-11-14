package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPreviewTest {
    private fun plan(vararg ids: Long) = TransactionRetentionPlanner.Plan(ids.toList(), 0L)

    @Test fun emptyPlanSaysNothingWouldBePruned() {
        val s = RetentionPreview.summarize(plan(), emptyList())
        assertEquals(0, s.count)
        assertEquals("Nothing would be pruned right now.", RetentionPreview.describe(s))
    }

    @Test fun describeSingleYearAndPlural() {
        val s = RetentionPreview.Summary(1240, mapOf(2023 to 1240))
        assertEquals(
            "This would delete 1,240 transactions from 2023. Back up first if you want to keep them.",
            RetentionPreview.describe(s),
        )
        assertTrue(RetentionPreview.describe(RetentionPreview.Summary(1, mapOf(2022 to 1))).contains("1 transaction from"))
    }

    @Test fun describeYearRange() {
        val s = RetentionPreview.Summary(5, mapOf(2021 to 2, 2023 to 3))
        assertTrue(RetentionPreview.describe(s).contains("from 2021–2023"))
    }

    private fun tx(id: Long, iso: String) = Transaction(
        id = id,
        amount = BigDecimal("50"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = Instant.parse(iso).toEpochMilli(),
    )

    @Test fun summarizeCountsOnlyPlannedRowsGroupedByYear() {
        val rows = listOf(
            tx(1, "2021-03-01T00:00:00Z"), tx(2, "2021-12-31T23:00:00Z"),
            tx(3, "2023-01-05T00:00:00Z"), tx(4, "2025-06-01T00:00:00Z"),
        )
        val s = RetentionPreview.summarize(plan(1, 2, 3), rows)
        assertEquals(3, s.count)
        assertEquals(mapOf(2021 to 2, 2023 to 1), s.countByYear)
    }

    @Test fun describeWithBackupContextReplacesTheGenericAdvice() {
        val s = RetentionPreview.Summary(10, mapOf(2023 to 10))
        val day = 24L * 60 * 60 * 1000
        val now = 1_700_000_000_000L
        val never = RetentionPreview.describe(s, RetentionPreview.BackupContext(RetentionBackupGate.Status.NEVER, 0L, now))
        assertTrue(never, never.startsWith("This would delete 10 transactions from 2023."))
        assertTrue(never, never.contains("paused") && !never.contains("Back up first"))
        val stale = RetentionPreview.describe(
            s, RetentionPreview.BackupContext(RetentionBackupGate.Status.STALE, now - 50 * day, now),
        )
        assertTrue(stale, stale.contains("50 days old"))
        val fresh = RetentionPreview.describe(
            s, RetentionPreview.BackupContext(RetentionBackupGate.Status.FRESH, now - day, now),
        )
        assertTrue(fresh, fresh.contains("will run"))
    }

    @Test fun nothingToPruneIgnoresTheBackupContext() {
        val ctx = RetentionPreview.BackupContext(RetentionBackupGate.Status.NEVER, 0L, 1L)
        assertEquals("Nothing would be pruned right now.", RetentionPreview.describe(RetentionPreview.Summary(0, emptyMap()), ctx))
    }
}
