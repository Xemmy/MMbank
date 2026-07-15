package com.xemmy.mmbank.domain.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single reactive source of truth for the opt-in app lock, mirroring the
 * hide-balances pattern on AppContainer: the UI collects [locked] and shows
 * the biometric lock screen whenever it is true.
 *
 * Lock rules (all no-ops while the feature is disabled):
 *  - the app has been away (backgrounded, or the process was killed) longer
 *    than [timeoutMillis] -> the next foreground locks
 *  - [timeoutMillis] of foreground inactivity (via [sessionMonitor]) -> locked
 *  - successful biometric/device-credential auth ([onUnlocked]) -> unlocked
 *
 * Note the timeout is a GRACE window, not an every-open gate: switching away
 * and back inside the window keeps the session unlocked, so a long timeout
 * (e.g. 12 hours) means the user authenticates at most a couple of times a
 * day instead of on every single return. The last-active instant is persisted
 * through [lastActive] so the grace survives the process being killed; a
 * first-ever launch (or a clock moved backwards) fails safe to locked.
 *
 * Pure Kotlin: preferences, the clock and the timer are injected, so every
 * transition here is unit-testable on the JVM.
 */
class AppLockController(
    private val sessionMonitor: SessionMonitor,
    private val isEnabled: () -> Boolean,
    private val timeoutMillis: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val lastActive: LastActiveStore = InMemoryLastActive(),
) {

    private val _locked = MutableStateFlow(isEnabled() && graceExpired())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * True when more than the timeout has elapsed since the app was last
     * active. A never-recorded (first launch) or backwards-moving clock is
     * treated as expired so the lock fails safe to engaged.
     */
    private fun graceExpired(): Boolean {
        val last = lastActive.value
        if (last <= 0L) return true
        val elapsed = now() - last
        return elapsed < 0L || elapsed >= timeoutMillis()
    }

    /**
     * App returned to the foreground. Within the grace window the session
     * simply resumes its idle timer; past it, auth is required.
     */
    fun onAppForegrounded() {
        if (!isEnabled() || _locked.value) return
        if (graceExpired()) {
            sessionMonitor.stopMonitoring()
            _locked.value = true
        } else {
            armTimer()
        }
    }

    /**
     * App left the foreground. Instead of locking outright, stamp the moment we
     * left and stop the (now pointless) idle timer; the lock only engages on the
     * next foreground if the grace window has since passed.
     */
    fun onAppBackgrounded() {
        sessionMonitor.stopMonitoring()
        if (isEnabled() && !_locked.value) lastActive.value = now()
    }

    /** A biometric/device-credential prompt succeeded. */
    fun onUnlocked() {
        _locked.value = false
        lastActive.value = now()
        armTimer()
    }

    /** Any touch/input while the app is open defers the idle lockout. */
    fun onUserInteraction() {
        if (isEnabled() && !_locked.value) sessionMonitor.onUserInteraction()
    }

    /**
     * The Settings toggle (or timeout picker) changed. Disabling releases an
     * engaged lock immediately so the user can't strand themselves; enabling
     * or retiming re-stamps the grace window and re-arms the idle timer for the
     * current session.
     */
    fun onSettingsChanged() {
        if (!isEnabled()) {
            sessionMonitor.stopMonitoring()
            _locked.value = false
        } else if (!_locked.value) {
            lastActive.value = now()
            armTimer()
        }
    }

    private fun armTimer() {
        if (!isEnabled()) return
        sessionMonitor.startMonitoring(timeoutMillis()) { _locked.value = true }
    }

    /** Persisted last-active instant; abstracted so the controller stays pure-JVM. */
    interface LastActiveStore {
        var value: Long
    }

    /** Default in-memory store — used in tests and when no persistence is wired. */
    class InMemoryLastActive(override var value: Long = 0L) : LastActiveStore
}
