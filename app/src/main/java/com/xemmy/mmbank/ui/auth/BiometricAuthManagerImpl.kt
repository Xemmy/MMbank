package com.xemmy.mmbank.ui.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xemmy.mmbank.domain.auth.BiometricAuthManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real [BiometricAuthManager] over androidx.biometric's [BiometricPrompt].
 * Fingerprint/face first, falling back to the device PIN/pattern/password
 * ([DEVICE_CREDENTIAL]), so a failed sensor or re-enrolled biometrics can
 * never strand the user outside their own ledger.
 *
 * Must be constructed with the hosting [FragmentActivity] (a BiometricPrompt
 * requirement) and [promptAuth] must be called from the main thread — both
 * are naturally true from a composable's LaunchedEffect.
 */
class BiometricAuthManagerImpl(private val activity: FragmentActivity) : BiometricAuthManager {

    override suspend fun promptAuth(title: String, subtitle: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Cancellation, lockout, hardware errors: the prompt is
                        // gone, resolve as not-authenticated (caller may retry).
                        if (continuation.isActive) continuation.resume(false)
                    }

                    // onAuthenticationFailed (a single bad read) keeps the
                    // prompt on screen; deliberately not resumed.
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                // No negative button: DEVICE_CREDENTIAL supplies the fallback
                // action (and the API forbids combining the two).
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(promptInfo)
            continuation.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }

    companion object {
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /**
         * Whether the device can show any unlock prompt at all (biometrics
         * enrolled OR a screen lock set). Gates the Settings toggle so the
         * lock can never be enabled somewhere it could never be opened.
         */
        fun canAuthenticate(context: Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
