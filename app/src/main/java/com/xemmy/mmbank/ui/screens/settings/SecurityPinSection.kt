package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.auth.PinPad

/**
 * App PIN and screenshot blocking, under Security.
 *
 * - **App PIN**: the lock's own 4-8 digit secret. It works on phones with no
 *   screen lock, is separate from the phone's unlock PIN, and keeps the lock
 *   openable if the phone's screen lock is later removed.
 * - **Block screenshots**: `FLAG_SECURE` app-wide, which also blanks MMbank's
 *   thumbnail in Recents.
 */
@Composable
fun SecurityPinSection(
    hasAppPin: Boolean,
    secureScreen: Boolean,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onSecureScreen: (Boolean) -> Unit,
) {
    var settingPin by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("App PIN", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (hasAppPin) {
                "Set. The lock screen offers \"Use app PIN\", and opens with it when this phone has no screen lock."
            } else {
                "A 4-8 digit PIN just for MMbank. Lets the app lock work without a phone screen lock, " +
                    "and keeps you from being locked out if you remove one."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { settingPin = true }) { Text(if (hasAppPin) "Change PIN" else "Set PIN") }
            if (hasAppPin) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove PIN") }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Block screenshots", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Stops screenshots and screen recording of MMbank and hides it in Recents. " +
                    "The home-screen widget isn't covered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = secureScreen, onCheckedChange = onSecureScreen)
    }

    if (settingPin) {
        SetPinDialog(
            onDone = { pin ->
                settingPin = false
                onSetPin(pin)
            },
            onDismiss = { settingPin = false },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the app PIN?") },
            text = {
                Text(
                    "If this phone has no screen lock or biometrics, the app lock will be turned off too, " +
                        "so you can't be locked out.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemovePin() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Enter the new PIN, then again to confirm. */
@Composable
private fun SetPinDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            PinPad(
                title = if (first == null) "Choose a 4-8 digit PIN" else "Enter it again",
                message = message,
                messageIsError = message != null,
                onSubmit = { pin ->
                    val chosen = first
                    when {
                        chosen == null -> {
                            first = pin
                            message = null
                        }
                        chosen == pin -> onDone(pin)
                        else -> {
                            first = null
                            message = "The PINs didn't match. Start again."
                        }
                    }
                },
            )
        },
    )
}
