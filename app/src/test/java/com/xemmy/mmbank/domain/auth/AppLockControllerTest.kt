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

    private class FakeLastActive(override var value: Long = 0L) : AppLockController.LastActiveStore

    /** Mutable so tests can advance wall-clock time between transitions. */
    private class FakeClock(var nowMs: Long = 10_000_000L) { fun now(): Long = nowMs }

    private fun controller(
        monitor: FakeSessionMonitor = FakeSessionMonitor(),
        enabled: Boolean = true,
        timeoutMs: Long = 300_000L,
        clock: FakeClock = FakeClock(),
        lastActive: FakeLastActive = FakeLastActive(),
    ): AppLockController =
        AppLockController(monitor, { enabled }, { timeoutMs }, clock::now, lastActive)

    @Test
    fun `first launch with lock enabled begins locked`() {
        // No recorded last-active instant -> fail safe to locked.
        assertTrue(controller(enabled = true).locked.value)
    }

    @Test
    fun `process start with lock disabled begins unlocked`() {
        assertFalse(controller(enabled = false).locked.value)
    }

    @Test
    fun `cold start within the grace window begins unlocked`() {
        // App was last active 1 min ago; a 5-min timeout hasn't lapsed, so the
        // user should NOT be re-prompted just for reopening.
        val clock = FakeClock(nowMs = 1_060_000L)
        val lastActive = FakeLastActive(value = 1_000_000L) // 60s earlier
        assertFalse(controller(timeoutMs = 300_000L, clock = clock, lastActive = lastActive).locked.value)
    }

    @Test
    fun `cold start past the grace window begins locked`() {
        val clock = FakeClock(nowMs = 1_400_000L)
        val lastActive = FakeLastActive(value = 1_000_000L) // 400s earlier > 300s
        assertTrue(controller(timeoutMs = 300_000L, clock = clock, lastActive = lastActive).locked.value)
    }

    @Test
    fun `unlock releases the gate and arms the idle timer`() {
        val monitor = FakeSessionMonitor()
        val c = controller(monitor)
        c.onUnlocked()
        assertFalse(c.locked.value)
        assertEquals(1, monitor.started)
        assertEquals(300_000L, monitor.lastTimeoutMs)
    }

    @Test
    fun `backgrounding stops the timer and stamps last-active without locking`() {
        val monitor = FakeSessionMonitor()
        val clock = FakeClock(nowMs = 5_000_000L)
        val lastActive = FakeLastActive()
        val c = controller(monitor, clock = clock, lastActive = lastActive)
        c.onUnlocked()
        c.onAppBackgrounded()
        // Grace period: leaving does NOT immediately re-lock.
        assertFalse(c.locked.value)
        assertTrue(monitor.stopped >= 1)
        assertEquals(5_000_000L, lastActive.value)
    }

    @Test
    fun `return within the grace window stays unlocked and re-arms`() {
        val monitor = FakeSessionMonitor()
        val clock = FakeClock()
        val c = controller(monitor, timeoutMs = 300_000L, clock = clock)
        c.onUnlocked()
        c.onAppBackgrounded()            // stamps last-active = now
        clock.nowMs += 60_000L           // gone 1 min (< 5 min)
        val startsBefore = monitor.started
        c.onAppForegrounded()
        assertFalse(c.locked.value)
        assertEquals(startsBefore + 1, monitor.started)
    }

    @Test
    fun `return past the grace window locks`() {
        val monitor = FakeSessionMonitor()
        val clock = FakeClock()
        val c = controller(monitor, timeoutMs = 300_000L, clock = clock)
        c.onUnlocked()
        c.onAppBackgrounded()
        clock.nowMs += 600_000L           // gone 10 min (> 5 min)
        c.onAppForegrounded()
        assertTrue(c.locked.value)
    }

    @Test
    fun `backgrounding while disabled does not lock`() {
        val c = controller(enabled = false)
        c.onAppBackgrounded()
        assertFalse(c.locked.value)
    }

    @Test
    fun `idle lockout fired by the monitor engages the lock`() {
        val monitor = FakeSessionMonitor()
        val c = controller(monitor)
        c.onUnlocked()
        monitor.lockout!!.invoke()
        assertTrue(c.locked.value)
    }

    @Test
    fun `interaction is forwarded only while unlocked and enabled`() {
        val monitor = FakeSessionMonitor()
        val c = controller(monitor)      // starts locked (no last-active)
        c.onUserInteraction()            // still locked: not forwarded
        assertEquals(0, monitor.interactions)
        c.onUnlocked()
        c.onUserInteraction()
        assertEquals(1, monitor.interactions)
    }

    @Test
    fun `foregrounding while locked does not arm a timer`() {
        val monitor = FakeSessionMonitor()
        val c = controller(monitor)      // starts locked
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
        val c = AppLockController(monitor, { true }, { timeout }, lastActive = FakeLastActive())
        c.onUnlocked()
        timeout = 120_000L
        c.onSettingsChanged()
        assertEquals(120_000L, monitor.lastTimeoutMs)
    }
}
