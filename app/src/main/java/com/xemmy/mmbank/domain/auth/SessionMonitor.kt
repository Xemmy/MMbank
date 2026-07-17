package com.xemmy.mmbank.domain.auth

/**
 * Abstraction over idle-timeout session locking: call [onUserInteraction] on
 * every touch/input event, [startMonitoring] to arm a lockout timer, and
 * [stopMonitoring] to disarm it. Pure domain interface; no Android
 * Handler/lifecycle wiring here yet.
 */
interface SessionMonitor {
    fun onUserInteraction()
    fun startMonitoring(timeoutMs: Long, onLockout: () -> Unit)
    fun stopMonitoring()
}

/** No-op stub: never arms a timer, never fires [SessionMonitor.startMonitoring]'s callback. */
class StubSessionMonitor : SessionMonitor {
    override fun onUserInteraction() = Unit
    override fun startMonitoring(timeoutMs: Long, onLockout: () -> Unit) = Unit
    override fun stopMonitoring() = Unit
}
