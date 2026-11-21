package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.intelligence.CaptureHealthEvaluator.Inputs
import com.xemmy.mmbank.domain.intelligence.CaptureHealthEvaluator.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHealthEvaluatorTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_000 * day

    /** A daily capturer, last capture [sinceLast] ago. */
    private fun daily(sinceLast: Long, count: Int = 20) = (0 until count).map { now - sinceLast - it * day }

    private fun inputs(
        notificationAccess: Boolean = true,
        sms: Boolean = true,
        battery: Boolean = true,
        lastEvent: Long = now - hour,
        connected: Long = now - 10 * day,
        disconnected: Long = 0,
        captured: List<Long> = daily(sinceLast = 6 * hour),
        snoozedUntil: Long = 0,
        at: Long = now,
    ) = Inputs(notificationAccess, sms, battery, lastEvent, connected, disconnected, captured, snoozedUntil, at)

    private fun eval(i: Inputs) = CaptureHealthEvaluator.evaluate(i)

    @Test
    fun `healthy when capture is flowing`() {
        val h = eval(inputs())
        assertEquals(State.HEALTHY, h.state)
        assertFalse(h.showBanner)
    }

    @Test
    fun `a fresh install with no history and no heartbeat is healthy`() {
        assertEquals(State.HEALTHY, eval(inputs(lastEvent = 0, connected = 0, captured = emptyList())).state)
    }

    @Test
    fun `no access at all beats everything and ignores the snooze`() {
        val h = eval(inputs(notificationAccess = false, sms = false, snoozedUntil = now + day, captured = daily(10 * day)))
        assertEquals(State.NO_ACCESS, h.state)
        assertTrue(h.showBanner)
    }

    @Test
    fun `sms alone is enough access`() {
        assertEquals(State.HEALTHY, eval(inputs(notificationAccess = false)).state)
    }

    @Test
    fun `disconnected when Android's last word was a disconnect`() {
        val h = eval(inputs(connected = now - 2 * day, disconnected = now - day))
        assertEquals(State.DISCONNECTED, h.state)
        assertTrue(h.showBanner)
    }

    @Test
    fun `reconnected after a disconnect is fine`() {
        assertEquals(State.HEALTHY, eval(inputs(connected = now - hour, disconnected = now - day)).state)
    }

    @Test
    fun `disconnected when no notification at all arrived for a day`() {
        assertEquals(State.DISCONNECTED, eval(inputs(lastEvent = now - 25 * hour)).state)
        assertEquals(State.HEALTHY, eval(inputs(lastEvent = now - 23 * hour)).state)
    }

    @Test
    fun `listener silence doesn't matter without notification access`() {
        assertEquals(State.HEALTHY, eval(inputs(notificationAccess = false, lastEvent = now - 5 * day)).state)
    }

    @Test
    fun `quiet when a daily capturer stops for more than three days`() {
        val h = eval(inputs(captured = daily(sinceLast = 4 * day)))
        assertEquals(State.QUIET, h.state)
        assertEquals(4, h.quietDays)
        assertEquals(State.HEALTHY, eval(inputs(captured = daily(sinceLast = 2 * day))).state)
    }

    @Test
    fun `a weekly capturer is not flagged early`() {
        val weekly = (0 until 10).map { now - 5 * day - it * 7 * day }
        assertEquals(State.HEALTHY, eval(inputs(captured = weekly)).state)
        val longSilence = (0 until 10).map { now - 22 * day - it * 7 * day }
        assertEquals(State.QUIET, eval(inputs(captured = longSilence)).state)
    }

    @Test
    fun `too little history is never quiet`() {
        assertEquals(State.HEALTHY, eval(inputs(captured = daily(sinceLast = 30 * day, count = 4))).state)
    }

    @Test
    fun `a very long silence still counts`() {
        assertEquals(State.QUIET, eval(inputs(captured = daily(sinceLast = 200 * day))).state)
    }

    @Test
    fun `snooze hides quiet and disconnected until it expires`() {
        val quiet = inputs(captured = daily(sinceLast = 5 * day), snoozedUntil = now + hour)
        assertFalse(eval(quiet).showBanner)
        assertEquals(State.QUIET, eval(quiet).state)
        assertTrue(eval(quiet.copy(now = now + 2 * hour)).showBanner)
    }

    @Test
    fun `a heartbeat from the future means the clock moved back, not a fault`() {
        assertEquals(State.HEALTHY, eval(inputs(lastEvent = now + 3 * day)).state)
    }

    @Test
    fun `battery hint only when not exempt`() {
        val quiet = daily(sinceLast = 5 * day)
        assertTrue(eval(inputs(captured = quiet, battery = false)).message.contains("battery"))
        assertFalse(eval(inputs(captured = quiet, battery = true)).message.contains("battery"))
    }

    @Test
    fun `heartbeat throttle`() {
        assertTrue(CaptureHealthEvaluator.shouldRecordHeartbeat(0, now))
        assertFalse(CaptureHealthEvaluator.shouldRecordHeartbeat(now - 60_000, now))
        assertTrue(CaptureHealthEvaluator.shouldRecordHeartbeat(now - CaptureHealthEvaluator.HEARTBEAT_INTERVAL_MILLIS, now))
        assertTrue(CaptureHealthEvaluator.shouldRecordHeartbeat(now + day, now))
    }
}
