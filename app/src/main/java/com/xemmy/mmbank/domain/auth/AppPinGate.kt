package com.xemmy.mmbank.domain.auth

/** What happened when a PIN was tried on the lock screen. */
sealed interface PinCheck {
    data object Ok : PinCheck

    /** Wrong PIN; [lockedForMillis] > 0 when this try started a lockout. */
    data class Wrong(val lockedForMillis: Long) : PinCheck

    /** Too many wrong tries: nothing is checked until the lockout ends. */
    data class LockedOut(val remainingMillis: Long) : PinCheck
}

/**
 * The lock screen's PIN check: [PinAttemptLimiter] first (a locked-out try
 * isn't even compared), then [PinHasher]. Returns the verdict and the next
 * limiter state for the caller to persist. Pure and JVM-testable.
 */
object AppPinGate {

    data class Outcome(val result: PinCheck, val state: PinAttemptLimiter.State)

    fun check(pin: String, storedHash: String?, state: PinAttemptLimiter.State, now: Long): Outcome {
        val remaining = PinAttemptLimiter.remainingLockout(state, now)
        if (remaining > 0L) return Outcome(PinCheck.LockedOut(remaining), state)
        if (storedHash != null && PinHasher.verify(pin, storedHash)) {
            return Outcome(PinCheck.Ok, PinAttemptLimiter.onSuccess())
        }
        val next = PinAttemptLimiter.onFailure(state, now)
        return Outcome(PinCheck.Wrong(PinAttemptLimiter.remainingLockout(next, now)), next)
    }

    /**
     * Whether an enabled app lock can actually be opened. With neither an app
     * PIN nor a system authenticator (biometrics or a screen lock) -- e.g. the
     * screen lock was removed after the app lock was turned on -- the lock
     * must stay off rather than strand the user outside their own ledger.
     */
    fun lockUsable(hasAppPin: Boolean, canAuthenticate: Boolean): Boolean = hasAppPin || canAuthenticate

    /** "Try again in 2 min" / "Try again in 30 s". */
    fun waitText(millis: Long): String {
        val seconds = (millis + 999) / 1000
        return if (seconds >= 60) "Try again in ${(seconds + 59) / 60} min" else "Try again in $seconds s"
    }
}
