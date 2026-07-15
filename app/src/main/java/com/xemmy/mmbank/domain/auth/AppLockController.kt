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
 *  - process start with the lock enabled -> locked
 *  - app backgrounded -> locked
 *  - [timeoutMillis] of foreground inactivity (via [sessionMonitor]) -> locked
 *  - successful biometric/device-credential auth ([onUnlocked]) -> unlocked
 *
 * Pure Kotlin: preferences and the timer are injected, so every transition
 * here is unit-testable on the JVM.
 */
class AppLockController(
    private val sessionMonitor: SessionMonitor,
    private val isEnabled: () -> Boolean,
    private val timeoutMillis: () -> Long,
) {

    private val _locked = MutableStateFlow(isEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** App returned to the foreground; an unlocked session resumes its idle timer. */
    fun onAppForegrounded() {
        if (isEnabled() && !_locked.value) armTimer()
    }

    /** App left the foreground: engage the lock and stop the (now pointless) timer. */
    fun onAppBackgrounded() {
        sessionMonitor.stopMonitoring()
        if (isEnabled()) _locked.value = true
    }

    /** A biometric/device-credential prompt succeeded. */
    fun onUnlocked() {
        _locked.value = false
        armTimer()
    }

    /** Any touch/input while the app is open defers the idle lockout. */
    fun onUserInteraction() {
        if (isEnabled() && !_locked.value) sessionMonitor.onUserInteraction()
    }

    /**
     * The Settings toggle (or timeout picker) changed. Disabling releases an
     * engaged lock immediately so the user can't strand themselves; enabling
     * or retiming re-arms the idle timer for the current session.
     */
    fun onSettingsChanged() {
        if (!isEnabled()) {
            sessionMonitor.stopMonitoring()
            _locked.value = false
        } else if (!_locked.value) {
            armTimer()
        }
    }

    private fun armTimer() {
        if (!isEnabled()) return
        sessionMonitor.startMonitoring(timeoutMillis()) { _locked.value = true }
    }
}
