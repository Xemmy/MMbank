package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
// Subpage: Demo mode (biometric-gated sample-data toggle)
// ---------------------------------------------------------------------------

@Composable
fun DemoSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Demo mode", onBack = onBack) {
        DemoModeSection(viewModel)
        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Switch that swaps the real ledger, rules and people for disposable sample
 * data. Turning it ON is immediate. Turning it OFF — revealing the real data —
 * requires passing the biometric/device-credential prompt, so someone handed
 * the phone mid-demo can't get back to the real accounts. Real data is never
 * modified, only hidden; the whole feature needs a device lock so demo mode can
 * always be exited.
 */
@Composable
private fun DemoModeSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val canAuthenticate = remember { BiometricAuthManagerImpl.canAuthenticate(context) }
    var disabling by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Demo mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (canAuthenticate) {
                            "Hide your real accounts, transactions, rules and people behind a batch " +
                                "of randomly generated sample data — handy for screenshots or a " +
                                "walkthrough. Turning it off asks for your fingerprint, face or " +
                                "device PIN, then restores your real data untouched."
                        } else {
                            "Unavailable: set up a screen lock or enroll biometrics on this device first."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = viewModel.demoModeEnabled,
                    enabled = canAuthenticate && !viewModel.busy && !disabling && activity != null,
                    onCheckedChange = { wantEnabled ->
                        if (wantEnabled) {
                            viewModel.enableDemoMode()
                        } else if (activity != null) {
                            disabling = true
                            scope.launch {
                                try {
                                    val ok = BiometricAuthManagerImpl(activity).promptAuth(
                                        title = "Exit demo mode",
                                        subtitle = "Confirm it's you to reveal your real data",
                                    )
                                    if (ok) viewModel.disableDemoMode()
                                } finally {
                                    disabling = false
                                }
                            }
                        }
                    },
                )
            }
            if (viewModel.demoModeEnabled) {
                Text(
                    "Demo mode is on — your real data is safely hidden. Switch off (with biometrics) " +
                        "to restore it; switching on again generates a fresh set of sample data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
