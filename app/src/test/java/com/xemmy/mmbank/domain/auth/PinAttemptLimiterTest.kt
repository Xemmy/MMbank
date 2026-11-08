package com.xemmy.mmbank.domain.auth

import com.xemmy.mmbank.domain.auth.PinAttemptLimiter.State
import org.junit.Assert.assertEquals
import org.junit.Test

class PinAttemptLimiterTest {

    private val now = 1_000_000_000L

    @Test
    fun `the first five wrong tries are free`() {
        var s = State()
        repeat(5) {
            s = PinAttemptLimiter.onFailure(s, now)
            assertEquals(0L, PinAttemptLimiter.remainingLockout(s, now))
        }
        assertEquals(5, s.failedAttempts)
    }

    @Test
    fun `then the lockout doubles from 30 seconds`() {
        assertEquals(30_000L, PinAttemptLimiter.lockoutFor(6))
        assertEquals(60_000L, PinAttemptLimiter.lockoutFor(7))
        assertEquals(120_000L, PinAttemptLimiter.lockoutFor(8))
        val s = PinAttemptLimiter.onFailure(State(failedAttempts = 5), now)
        assertEquals(30_000L, PinAttemptLimiter.remainingLockout(s, now))
        assertEquals(10_000L, PinAttemptLimiter.remainingLockout(s, now + 20_000))
        assertEquals(0L, PinAttemptLimiter.remainingLockout(s, now + 30_000))
    }

    @Test
    fun `the lockout is capped at 15 minutes`() {
        assertEquals(PinAttemptLimiter.MAX_LOCKOUT_MILLIS, PinAttemptLimiter.lockoutFor(12))
        assertEquals(PinAttemptLimiter.MAX_LOCKOUT_MILLIS, PinAttemptLimiter.lockoutFor(1_000))
    }

    @Test
    fun `a correct PIN resets everything`() {
        assertEquals(State(), PinAttemptLimiter.onSuccess())
    }

    @Test
    fun `a clock moved backwards can't stretch the lockout`() {
        val s = State(failedAttempts = 9, lockedUntil = now + 3_600_000)
        assertEquals(PinAttemptLimiter.MAX_LOCKOUT_MILLIS, PinAttemptLimiter.remainingLockout(s, now))
    }
}
