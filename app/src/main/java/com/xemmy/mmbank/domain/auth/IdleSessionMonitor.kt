package com.xemmy.mmbank.domain.auth

/**
 * Real [SessionMonitor]: locks out after [startMonitoring]'s timeout elapses
 * with no [onUserInteraction]. The clock and the timer are injected so the
 * countdown logic is pure-JVM testable — production passes a main-thread
 * Handler scheduler; tests pass a fake clock and fire scheduled blocks by
 * hand.
 *
 * Interaction does NOT reschedule the timer on every touch (that would churn
 * a post/remove pair per frame while scrolling); instead the timer fires at
 * the original deadline, re-checks the last interaction, and re-arms itself
 * for however much idle time actually remains.
 */
class IdleSessionMonitor(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Schedules [block] to run after a delay; returns a cancel function. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
) : SessionMonitor {

    private var lastInteractionMillis = 0L
    private var timeoutMillis = 0L
    private var onLockout: (() -> Unit)? = null
    private var cancelPending: (() -> Unit)? = null

    override fun onUserInteraction() {
        lastInteractionMillis = clock()
    }

    override fun startMonitoring(timeoutMs: Long, onLockout: () -> Unit) {
        this.timeoutMillis = timeoutMs
        this.onLockout = onLockout
        lastInteractionMillis = clock()
        arm(timeoutMs)
    }

    override fun stopMonitoring() {
        cancelPending?.invoke()
        cancelPending = null
        onLockout = null
    }

    private fun arm(delayMillis: Long) {
        cancelPending?.invoke()
        cancelPending = schedule(delayMillis, ::onTimerFired)
    }

    private fun onTimerFired() {
        val lockout = onLockout ?: return
        val remaining = remainingMillis(lastInteractionMillis, clock(), timeoutMillis)
        if (remaining <= 0) {
            stopMonitoring()
            lockout()
        } else {
            arm(remaining)
        }
    }

    companion object {
        /** Pure decision: idle time left before lockout (<= 0 means lock now). */
        fun remainingMillis(lastInteractionMillis: Long, nowMillis: Long, timeoutMillis: Long): Long =
            timeoutMillis - (nowMillis - lastInteractionMillis)
    }
}
