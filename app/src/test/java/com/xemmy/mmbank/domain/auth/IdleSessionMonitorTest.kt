package com.xemmy.mmbank.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleSessionMonitorTest {

    /** Manual clock + scheduler: tests advance time and fire timers by hand. */
    private class Harness {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0

        val monitor = IdleSessionMonitor(
            clock = { now },
            schedule = { delay, block ->
                val entry = delay to block
                scheduled.add(entry)
                ({ if (scheduled.remove(entry)) cancelled++ })
            },
        )

        /** Fires the most recently armed timer, as the real Handler would at its deadline. */
        fun fireTimer() {
            val (_, block) = scheduled.removeAt(scheduled.size - 1)
            block()
        }
    }

    @Test
    fun `locks out when the full timeout passes with no interaction`() {
        val h = Harness()
        var lockedOut = false
        h.monitor.startMonitoring(1_000L) { lockedOut = true }
        h.now = 1_000L
        h.fireTimer()
        assertTrue(lockedOut)
    }

    @Test
    fun `interaction defers the lockout by re-arming for the remaining idle time`() {
        val h = Harness()
        var lockedOut = false
        h.monitor.startMonitoring(1_000L) { lockedOut = true }

        h.now = 600L
        h.monitor.onUserInteraction()

        h.now = 1_000L                 // original deadline: only 400ms idle so far
        h.fireTimer()
        assertFalse(lockedOut)
        assertEquals(1, h.scheduled.size)
        assertEquals(600L, h.scheduled.single().first)   // re-armed for the remainder

        h.now = 1_600L                 // 1000ms idle since the interaction
        h.fireTimer()
        assertTrue(lockedOut)
    }

    @Test
    fun `stopMonitoring cancels the pending timer and drops the callback`() {
        val h = Harness()
        var lockedOut = false
        h.monitor.startMonitoring(1_000L) { lockedOut = true }
        h.monitor.stopMonitoring()
        assertEquals(0, h.scheduled.size)
        assertEquals(1, h.cancelled)
        assertFalse(lockedOut)
    }

    @Test
    fun `restarting monitoring replaces the previous timer instead of stacking`() {
        val h = Harness()
        h.monitor.startMonitoring(1_000L) { }
        h.monitor.startMonitoring(2_000L) { }
        assertEquals(1, h.scheduled.size)
        assertEquals(2_000L, h.scheduled.single().first)
    }

    @Test
    fun `remainingMillis is the timeout minus elapsed idle time`() {
        assertEquals(400L, IdleSessionMonitor.remainingMillis(600L, 1_200L, 1_000L))
        assertEquals(0L, IdleSessionMonitor.remainingMillis(0L, 1_000L, 1_000L))
        assertTrue(IdleSessionMonitor.remainingMillis(0L, 5_000L, 1_000L) < 0)
    }
}
