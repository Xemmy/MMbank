package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.ai.RetentionBackupGate.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionBackupGateTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `no backup ever means never`() {
        assertEquals(Status.NEVER, RetentionBackupGate.status(0L, now))
        assertEquals(Status.NEVER, RetentionBackupGate.status(-1L, now))
    }

    @Test
    fun `a backup within thirty days is fresh, older is stale`() {
        assertEquals(Status.FRESH, RetentionBackupGate.status(now - 5 * day, now))
        assertEquals(Status.FRESH, RetentionBackupGate.status(now - 30 * day, now))
        assertEquals(Status.STALE, RetentionBackupGate.status(now - 31 * day, now))
        assertEquals(Status.STALE, RetentionBackupGate.status(now - 400 * day, now))
    }

    @Test
    fun `a backup stamped in the future is treated as fresh, not blocked forever`() {
        assertEquals(Status.FRESH, RetentionBackupGate.status(now + 3 * day, now))
    }

    @Test
    fun `only a fresh backup allows the prune`() {
        assertTrue(RetentionBackupGate.allowsPrune(Status.FRESH))
        assertFalse(RetentionBackupGate.allowsPrune(Status.STALE))
        assertFalse(RetentionBackupGate.allowsPrune(Status.NEVER))
    }

    @Test
    fun `the description says why pruning is paused and how old the backup is`() {
        assertTrue(RetentionBackupGate.describe(Status.NEVER, 0L, now).contains("paused"))
        val stale = RetentionBackupGate.describe(Status.STALE, now - 45 * day, now)
        assertTrue(stale, stale.contains("45 days") && stale.contains("paused"))
        assertTrue(RetentionBackupGate.describe(Status.FRESH, now - day, now).contains("will run"))
    }

    @Test
    fun `days since never goes negative`() {
        assertEquals(0, RetentionBackupGate.daysSince(now + day, now))
        assertEquals(3, RetentionBackupGate.daysSince(now - 3 * day - 1000, now))
    }
}
