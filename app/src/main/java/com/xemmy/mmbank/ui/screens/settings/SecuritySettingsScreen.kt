package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Subpage: Security (biometric app lock)
// ---------------------------------------------------------------------------

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Security", onBack = onBack) {
        AppLockSection(viewModel)
        SecurityPinSection(
            hasAppPin = viewModel.hasAppPin,
            secureScreen = viewModel.secureScreen,
            onSetPin = viewModel::setAppPin,
            onRemovePin = viewModel::removeAppPin,
            onSecureScreen = viewModel::updateSecureScreen,
        )
        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/**
 * Opt-in biometric app lock: the switch, and the idle-timeout picker while it
 * is on. ENABLING requires passing the biometric/device-credential prompt
 * right there — both to prove the prompt works on this device before it can
 * gate the app, and so a passer-by with the phone in hand can't silently arm
 * a lock. Disabling is immediate (the user already authenticated to be here).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLockSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val canAuthenticate = remember { BiometricAuthManagerImpl.canAuthenticate(context) }
    // An app PIN can open the lock on its own (see AppPinGate.lockUsable).
    val lockUsable = canAuthenticate || viewModel.hasAppPin
    var enabling by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("App lock", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    canAuthenticate ->
                        "Require your fingerprint, face, or device PIN once the app has " +
                            "been closed or idle beyond the auto-lock time below."
                    viewModel.hasAppPin ->
                        "Require your app PIN once the app has been closed or idle beyond the auto-lock time below."
                    else -> "Unavailable: set an app PIN below, or a screen lock or biometrics on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = viewModel.appLockEnabled,
            enabled = lockUsable && !enabling && activity != null,
            onCheckedChange = { wantEnabled ->
                if (!wantEnabled) {
                    viewModel.updateAppLockEnabled(false)
                } else if (!canAuthenticate && viewModel.hasAppPin) {
                    // No system prompt on this phone: the app PIN set below opens the lock.
                    viewModel.updateAppLockEnabled(true)
                } else if (activity != null) {
                    enabling = true
                    scope.launch {
                        try {
                            val ok = BiometricAuthManagerImpl(activity).promptAuth(
                                title = "Enable app lock",
                                subtitle = "Confirm it's you — this prompt will guard the app",
                            )
                            if (ok) viewModel.updateAppLockEnabled(true)
                        } finally {
                            enabling = false
                        }
                    }
                }
            },
        )
    }

    if (viewModel.appLockEnabled) {
        Text(
            "Ask again after inactivity",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Switching away and back within this window won't ask again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            APP_LOCK_TIMEOUT_OPTIONS_MIN.forEach { minutes ->
                FilterChip(
                    selected = viewModel.appLockTimeoutMinutes == minutes,
                    onClick = { viewModel.updateAppLockTimeout(minutes) },
                    label = { Text(appLockTimeoutLabel(minutes)) },
                )
            }
        }
    }
}
