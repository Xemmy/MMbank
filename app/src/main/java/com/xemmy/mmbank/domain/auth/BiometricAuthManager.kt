package com.xemmy.mmbank.domain.auth

/**
 * Abstraction over a device-level biometric/PIN prompt gating app entry or
 * sensitive actions. The real implementation (wired to
 * androidx.biometric.BiometricPrompt) is a future, Android-framework-touching
 * phase; this scaffolding only defines the contract.
 */
interface BiometricAuthManager {
    suspend fun promptAuth(title: String, subtitle: String): Boolean
}

/** Always-succeeds stub so callers can integrate against the interface today. */
class StubBiometricAuthManager : BiometricAuthManager {
    override suspend fun promptAuth(title: String, subtitle: String): Boolean = true
}
