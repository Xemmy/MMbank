package com.xemmy.mmbank.domain.auth

/**
 * Slows down guessing the app PIN. The first [FREE_ATTEMPTS] wrong tries cost
 * nothing; after that each wrong try locks entry for 30 s, doubling every
 * time (30 s, 1 min, 2 min ...) up to [MAX_LOCKOUT_MILLIS]. A correct PIN
 * resets everything. State is two plain numbers the caller persists, so a
 * restart doesn't reset the count. Pure and JVM-testable.
 */
object PinAttemptLimiter {

    const val FREE_ATTEMPTS = 5
    const val BASE_LOCKOUT_MILLIS = 30_000L
    const val MAX_LOCKOUT_MILLIS = 15L * 60 * 1000

    data class State(val failedAttempts: Int = 0, val lockedUntil: Long = 0L)

    /**
     * Milliseconds left before another try is allowed; 0 = try now. A
     * lockout that reaches further ahead than the maximum means the clock
     * moved back: it is clamped rather than trusted.
     */
    fun remainingLockout(state: State, now: Long): Long {
        val left = state.lockedUntil - now
        return when {
            left <= 0L -> 0L
            else -> minOf(left, MAX_LOCKOUT_MILLIS)
        }
    }

    /** The state after a wrong PIN at [now]. */
    fun onFailure(state: State, now: Long): State {
        val failed = state.failedAttempts + 1
        val lockout = lockoutFor(failed)
        return State(failed, if (lockout > 0L) now + lockout else 0L)
    }

    /** The state after a correct PIN: everything forgotten. */
    fun onSuccess(): State = State()

    /** How long the [failedAttempts]-th wrong try locks for. */
    fun lockoutFor(failedAttempts: Int): Long {
        if (failedAttempts <= FREE_ATTEMPTS) return 0L
        val doublings = (failedAttempts - FREE_ATTEMPTS - 1).coerceAtMost(20)
        return minOf(BASE_LOCKOUT_MILLIS shl doublings, MAX_LOCKOUT_MILLIS)
    }
}
