package com.xemmy.mmbank.domain.intelligence

/**
 * Notices when capture has stopped. MMbank fails silently: an OEM battery
 * manager revokes or kills the notification listener, and transactions just
 * stop arriving while every screen keeps looking fine. This turns what the
 * app can observe into one verdict:
 *
 * - [State.NO_ACCESS]: neither notification access nor the SMS permission is
 *   granted -- nothing can be captured at all.
 * - [State.DISCONNECTED]: notification access is granted but the listener
 *   isn't running -- Android's last word was "disconnected", or not a single
 *   notification (from any app) has reached it for a day.
 * - [State.QUIET]: the user captures regularly, but nothing has arrived for
 *   much longer than their usual gap.
 * - [State.HEALTHY]: otherwise, including a fresh install with no history.
 *
 * Deliberately slow to cry wolf: QUIET needs at least [MIN_CAPTURES] captures
 * in the last [HISTORY_DAYS] days and waits for max(3 × the median gap, 72 h),
 * so someone who captures once a week isn't flagged after three days. A
 * snooze hides DISCONNECTED and QUIET, never NO_ACCESS. Pure and JVM-testable.
 */
object CaptureHealthEvaluator {

    enum class State { HEALTHY, QUIET, DISCONNECTED, NO_ACCESS }

    data class Inputs(
        val notificationAccess: Boolean,
        val smsPermission: Boolean,
        val batteryExempt: Boolean,
        /** Last time any notification reached the listener (0 = never recorded). */
        val lastListenerEventAt: Long,
        val listenerConnectedAt: Long,
        val listenerDisconnectedAt: Long,
        /** When each SMS/push-captured transaction was recorded (`createdAt`), any order. */
        val capturedAt: List<Long>,
        val snoozedUntil: Long,
        val now: Long,
    )

    data class Health(
        val state: State,
        /** Whole days since the last capture, for [State.QUIET]. */
        val quietDays: Int? = null,
        /** False when healthy or snoozed. */
        val showBanner: Boolean,
        val message: String,
    )

    const val MIN_CAPTURES = 5
    const val HISTORY_DAYS = 90L
    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR
    private const val MIN_QUIET = 72 * HOUR
    private const val LISTENER_SILENCE = 24 * HOUR

    /** Heartbeats are written at most this often, so a busy phone doesn't hammer preferences. */
    const val HEARTBEAT_INTERVAL_MILLIS = 15L * 60 * 1000

    fun evaluate(i: Inputs): Health {
        if (!i.notificationAccess && !i.smsPermission) {
            return Health(
                State.NO_ACCESS,
                showBanner = true,
                message = "Capture is off: MMbank can't see bank notifications or SMS. " +
                    "Turn on notification access or the SMS permission.",
            )
        }
        val snoozed = i.now < i.snoozedUntil
        val batteryHint = if (i.batteryExempt) "" else " Exempting MMbank from battery optimisation helps keep it running."

        if (i.notificationAccess && listenerLooksDown(i)) {
            return Health(
                State.DISCONNECTED,
                showBanner = !snoozed,
                message = "Notification capture seems to have stopped. Android may have disconnected MMbank: " +
                    "open notification access and switch MMbank off and on again.$batteryHint",
            )
        }

        quietDays(i)?.let { days ->
            return Health(
                State.QUIET,
                quietDays = days,
                showBanner = !snoozed,
                message = "No bank messages captured for $days days, longer than usual. " +
                    "If you've been spending, capture may have stopped.$batteryHint",
            )
        }
        return Health(State.HEALTHY, showBanner = false, message = "Capture is working.")
    }

    private fun listenerLooksDown(i: Inputs): Boolean {
        val disconnectedLast = i.listenerDisconnectedAt > 0 && i.listenerDisconnectedAt > i.listenerConnectedAt
        // Never recorded (fresh install, just updated) is not evidence of anything.
        // A heartbeat "in the future" means the clock moved back: treat it as fresh.
        val silent = i.lastListenerEventAt > 0 && i.now - i.lastListenerEventAt > LISTENER_SILENCE
        return disconnectedLast || silent
    }

    private fun quietDays(i: Inputs): Int? {
        val past = i.capturedAt.filter { it <= i.now }.sorted()
        val last = past.lastOrNull() ?: return null
        // The baseline is the user's rhythm before the silence began, so a
        // very long silence still counts as one.
        val recent = past.filter { last - it <= HISTORY_DAYS * DAY }
        if (recent.size < MIN_CAPTURES) return null
        val gaps = recent.zipWithNext { a, b -> b - a }
        val medianGap = SpendAnomalyDetector.median(gaps.map { it.toDouble() }).toLong()
        val threshold = maxOf(3 * medianGap, MIN_QUIET)
        val since = i.now - last
        return if (since > threshold) (since / DAY).toInt() else null
    }

    /** Whether to write a new heartbeat now; also true when the clock moved backwards. */
    fun shouldRecordHeartbeat(lastRecordedAt: Long, now: Long): Boolean =
        lastRecordedAt <= 0 || now < lastRecordedAt || now - lastRecordedAt >= HEARTBEAT_INTERVAL_MILLIS
}
