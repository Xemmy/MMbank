package com.xemmy.mmbank.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Subpage: Capture & permissions
// ---------------------------------------------------------------------------

@Composable
fun CaptureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var smsGranted by remember { mutableStateOf(isSmsGranted(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    fun refreshPermissionState() {
        notificationAccessGranted = isNotificationAccessGranted(context)
        smsGranted = isSmsGranted(context)
        batteryExempt = isBatteryExempt(context)
    }

    // Re-check whenever the user comes back from system settings.
    LifecycleResumeEffect(Unit) {
        refreshPermissionState()
        onPauseOrDispose { }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionState() }

    SettingsSubpage(title = "Capture & permissions", onBack = onBack) {
        PermissionCard(
            title = "Notification access",
            granted = notificationAccessGranted,
            description = "Required to read bank app push notifications.",
            buttonLabel = "Open notification access settings",
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )

        if (!notificationAccessGranted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "⚠️ Sideload trap: toggle greyed out?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        "Modern Android blocks notification access for sideloaded apps behind " +
                            "a \"Restricted setting\" barrier. To unlock it:\n" +
                            "1. Open Settings > Apps > MMbank\n" +
                            "2. Tap the three dots (⋮) in the top-right corner\n" +
                            "3. Choose \"Allow restricted settings\" and authenticate\n" +
                            "4. Return here and enable notification access",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                        )
                    }) { Text("Open this app's settings page") }
                }
            }
        }

        PermissionCard(
            title = "SMS capture",
            granted = smsGranted,
            description = "Required to parse bank SMS messages.",
            buttonLabel = "Grant SMS permission",
            onClick = { smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) },
        )

        PermissionCard(
            title = "Battery optimization exemption",
            granted = batteryExempt,
            description = "Some phones kill notification access in the background to save power, " +
                "which silently stops transaction capture. MMbank does no background polling, " +
                "so exempting it keeps capture alive at negligible battery cost.",
            buttonLabel = "Exempt from battery optimization",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }.recoverCatching {
                    // Some OEM builds don't handle the direct request intent.
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }
}
