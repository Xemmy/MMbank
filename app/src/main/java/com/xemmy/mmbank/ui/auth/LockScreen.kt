package com.xemmy.mmbank.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.auth.AppPinGate
import com.xemmy.mmbank.domain.auth.PinCheck
import com.xemmy.mmbank.ui.theme.MMTheme
import kotlinx.coroutines.launch

/**
 * Full-screen opaque gate drawn OVER the app content while the app lock is
 * engaged, so the underlying navigation/composition state survives a
 * background->unlock round-trip. All pointer input is consumed here — nothing
 * reaches the ledger underneath.
 *
 * The biometric prompt fires automatically the moment the gate appears; the
 * button re-triggers it after a cancel/lockout.
 */
@Composable
fun LockScreen(
    /** Shows the biometric/device-credential prompt; true = authenticated. */
    authenticate: suspend () -> Boolean,
    onUnlocked: () -> Unit,
    /** The device can show a biometric/screen-lock prompt at all. */
    biometricAvailable: Boolean = true,
    /** An app PIN is set (see [com.xemmy.mmbank.domain.auth.PinHasher]). */
    pinAvailable: Boolean = false,
    /** Checks an app PIN, applying the attempt limiter. */
    checkPin: suspend (String) -> PinCheck = { PinCheck.Wrong(0L) },
) {
    val scope = rememberCoroutineScope()
    var prompting by remember { mutableStateOf(false) }
    // Straight to the PIN pad when there's no system prompt to show.
    var usePin by remember { mutableStateOf(pinAvailable && !biometricAvailable) }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    var pinBusy by remember { mutableStateOf(false) }

    fun submitPin(pin: String) {
        if (pinBusy) return
        pinBusy = true
        scope.launch {
            try {
                when (val result = checkPin(pin)) {
                    PinCheck.Ok -> onUnlocked()
                    is PinCheck.Wrong -> pinMessage = if (result.lockedForMillis > 0L) {
                        "Wrong PIN. ${AppPinGate.waitText(result.lockedForMillis)}."
                    } else {
                        "Wrong PIN"
                    }
                    is PinCheck.LockedOut -> pinMessage = AppPinGate.waitText(result.remainingMillis) + "."
                }
            } finally {
                pinBusy = false
            }
        }
    }

    fun tryUnlock() {
        if (prompting) return
        prompting = true
        scope.launch {
            try {
                if (authenticate()) onUnlocked()
            } finally {
                prompting = false
            }
        }
    }

    LaunchedEffect(Unit) { if (!usePin && biometricAvailable) tryUnlock() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MMTheme.colors.canvas)
            // Swallow every touch so the (state-preserved) app underneath is
            // unreachable while locked.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MMTheme.colors.textTertiary,
        )
        Text(
            "MMbank is locked",
            style = MaterialTheme.typography.titleLarge,
            color = MMTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        if (usePin) {
            PinPad(
                title = "Enter your app PIN",
                message = pinMessage,
                messageIsError = pinMessage != null,
                enabled = !pinBusy,
                onSubmit = ::submitPin,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (biometricAvailable) {
                TextButton(onClick = { usePin = false; tryUnlock() }) {
                    Text("Use fingerprint, face or device PIN")
                }
            }
        } else {
            Text(
                "Unlock with your fingerprint, face, or device PIN.",
                style = MaterialTheme.typography.bodyMedium,
                color = MMTheme.colors.textTertiary,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Button(
                onClick = { tryUnlock() },
                enabled = !prompting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MMTheme.colors.textPrimary,
                    contentColor = MMTheme.colors.canvas,
                ),
            ) {
                Text("Unlock")
            }
            if (pinAvailable) {
                TextButton(onClick = { usePin = true }) { Text("Use app PIN") }
            }
        }
    }
}
