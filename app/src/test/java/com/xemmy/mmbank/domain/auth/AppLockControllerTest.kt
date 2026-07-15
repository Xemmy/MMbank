package com.xemmy.mmbank.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockControllerTest {

    /** Records calls and exposes the lockout callback so tests can fire it. */
    private class FakeSessionMonitor : SessionMonitor {
        var interactions = 0
        var started = 0
        var stopped = 0
        var lastTimeoutMs = -1L
        var lockout: (() -> Unit)? = null

        override fun onUserInteraction() { interactions++ }
        override fun startMonitoring(timeoutMs: Long, onLockout: () -> Unit) {
            started++
            lastTimeoutMs = timeoutMs
            lockout = onLockout
        }
        override fun stopMonitoring() { stopped++; lockout = null }
    }

    private fun controller(
        monitor: FakeSessionMonitor = FakeSessionMonitor(),
        enabled: Boolean = true,
        timeoutMs: Long = 300_000L,
    ): Pair<AppLockController, FakeSessionMonitor> =
        AppLockController(monitor, { enabled }, { timeoutMs }) to monitor

    @Test
    fun `process start with lock enabled begins locked`() {
        val (c, _) = controller(enabled = true)
        assertTrue(c.locked.value)
    }

    @Test
    fun `process start with lock disabled begins unlocked`() {
        val (c, _) = controller(enabled = false)
        assertFalse(c.locked.value)
    }

    @Test
    fun `unlock releases the gate and arms the idle timer`() {
        val (c, monitor) = controller()
        c.onUnlocked()
        assertFalse(c.locked.value)
        assertEquals(1, monitor.started)
        assertEquals(300_000L, monitor.lastTimeoutMs)
    }

    @Test
    fun `backgrounding re-locks and stops the timer`() {
        val (c, monitor) = controller()
        c.onUnlocked()
        c.onAppBackgrounded()
        assertTrue(c.locked.value)
        assertTrue(monitor.stopped >= 1)
    }

    @Test
    fun `backgrounding while disabled does not lock`() {
        val (c, _) = controller(enabled = false)
        c.onAppBackgrounded()
        assertFalse(c.locked.value)
    }

    @Test
    fun `idle lockout fired by the monitor engages the lock`() {
        val (c, monitor) = controller()
        c.onUnlocked()
        monitor.lockout!!.invoke()
        assertTrue(c.locked.value)
    }

    @Test
    fun `interaction is forwarded only while unlocked and enabled`() {
        val (c, monitor) = controller()
        c.onUserInteraction()           // still locked: not forwarded
        assertEquals(0, monitor.interactions)
        c.onUnlocked()
        c.onUserInteraction()
        assertEquals(1, monitor.interactions)
    }

    @Test
    fun `foregrounding an unlocked session re-arms the timer`() {
        val (c, monitor) = controller()
        c.onUnlocked()
        val startsAfterUnlock = monitor.started
        c.onAppForegrounded()
        assertEquals(startsAfterUnlock + 1, monitor.started)
    }

    @Test
    fun `foregrounding while locked does not arm a timer`() {
        val (c, monitor) = controller()
        c.onAppForegrounded()
        assertEquals(0, monitor.started)
        assertTrue(c.locked.value)
    }

    @Test
    fun `disabling in settings releases an engaged lock`() {
        var enabled = true
        val monitor = FakeSessionMonitor()
        val c = AppLockController(monitor, { enabled }, { 60_000L })
        assertTrue(c.locked.value)
        enabled = false
        c.onSettingsChanged()
        assertFalse(c.locked.value)
        assertTrue(monitor.stopped >= 1)
    }

    @Test
    fun `changing the timeout while unlocked re-arms with the new duration`() {
        var timeout = 60_000L
        val monitor = FakeSessionMonitor()
        val c = AppLockController(monitor, { true }, { timeout })
        c.onUnlocked()
        timeout = 120_000L
        c.onSettingsChanged()
        assertEquals(120_000L, monitor.lastTimeoutMs)
    }
}
