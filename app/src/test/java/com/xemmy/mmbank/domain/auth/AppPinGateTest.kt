package com.xemmy.mmbank.domain.auth

import com.xemmy.mmbank.domain.auth.PinAttemptLimiter.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPinGateTest {

    private val now = 5_000_000L
    private val stored = PinHasher.hash("2580", iterations = 1_000)

    @Test
    fun `the right PIN opens and resets the count`() {
        val out = AppPinGate.check("2580", stored, State(failedAttempts = 3), now)
        assertEquals(PinCheck.Ok, out.result)
        assertEquals(State(), out.state)
    }

    @Test
    fun `a wrong PIN counts, and the sixth starts a lockout`() {
        var state = State()
        repeat(5) {
            val out = AppPinGate.check("0000", stored, state, now)
            assertEquals(PinCheck.Wrong(0L), out.result)
            state = out.state
        }
        val sixth = AppPinGate.check("0000", stored, state, now)
        assertEquals(PinCheck.Wrong(30_000L), sixth.result)
        assertEquals(6, sixth.state.failedAttempts)
    }

    @Test
    fun `while locked out even the right PIN isn't checked`() {
        val locked = State(failedAttempts = 6, lockedUntil = now + 10_000)
        val out = AppPinGate.check("2580", stored, locked, now)
        assertEquals(PinCheck.LockedOut(10_000L), out.result)
        assertEquals(locked, out.state)
        assertEquals(PinCheck.Ok, AppPinGate.check("2580", stored, locked, now + 10_000).result)
    }

    @Test
    fun `no stored PIN never opens`() {
        assertTrue(AppPinGate.check("2580", null, State(), now).result is PinCheck.Wrong)
    }

    @Test
    fun `the lock fails open with neither a PIN nor a system authenticator`() {
        assertFalse(AppPinGate.lockUsable(hasAppPin = false, canAuthenticate = false))
        assertTrue(AppPinGate.lockUsable(hasAppPin = true, canAuthenticate = false))
        assertTrue(AppPinGate.lockUsable(hasAppPin = false, canAuthenticate = true))
    }

    @Test
    fun `wait text`() {
        assertEquals("Try again in 30 s", AppPinGate.waitText(30_000))
        assertEquals("Try again in 1 s", AppPinGate.waitText(200))
        assertEquals("Try again in 2 min", AppPinGate.waitText(120_000))
        assertEquals("Try again in 2 min", AppPinGate.waitText(61_000))
    }

    @Test
    fun `after the clock moves back the stored lockout is clamped and persisted`() {
        val skewed = State(failedAttempts = 8, lockedUntil = now + 10L * 24 * 60 * 60 * 1000)
        val out = AppPinGate.check("2580", stored, skewed, now)
        assertTrue(out.result is PinCheck.LockedOut)
        assertEquals(now + PinAttemptLimiter.MAX_LOCKOUT_MILLIS, out.state.lockedUntil)
    }
}
