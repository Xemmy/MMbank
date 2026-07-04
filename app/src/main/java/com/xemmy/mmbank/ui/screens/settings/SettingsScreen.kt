package com.xemmy.mmbank.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var defaultCurrency by mutableStateOf(container.preferences.defaultCurrency)
    var apiKeyInput by mutableStateOf("")
    var secretKeyInput by mutableStateOf("")
    var hasBinanceKeys by mutableStateOf(container.credentialStore.hasBinanceCredentials)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun saveDefaultCurrency() {
        val code = defaultCurrency.trim().uppercase()
        if (code.length == 3) {
            container.preferences.defaultCurrency = code
            defaultCurrency = code
            statusMessage = "Default currency set to $code"
        } else {
            statusMessage = "Currency must be a 3-letter ISO code"
        }
    }

    fun saveBinanceKeys() {
        container.credentialStore.binanceApiKey = apiKeyInput
        container.credentialStore.binanceSecretKey = secretKeyInput
        hasBinanceKeys = container.credentialStore.hasBinanceCredentials
        apiKeyInput = ""
        secretKeyInput = ""
        statusMessage = if (hasBinanceKeys) "Binance keys saved (encrypted)" else "Both keys are required"
    }

    fun clearBinanceKeys() {
        container.credentialStore.clearBinanceCredentials()
        hasBinanceKeys = false
        statusMessage = "Binance keys removed"
    }

    /** Read-only round trip to /api/v3/account — verifies keys and pulls balances. */
    fun testBinanceConnection() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Contacting Binance…"
            container.portfolioRepository.syncSpotBalances()
                .onSuccess { statusMessage = "Connected — $it assets synced" }
                .onFailure { statusMessage = "Binance error: ${it.message}" }
            busy = false
        }
    }

    fun refreshRates() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Fetching rates…"
            container.ratesRepository.refresh(container.preferences.defaultCurrency)
                .onSuccess { statusMessage = "$it exchange rates updated" }
                .onFailure { statusMessage = "Rates error: ${it.message}" }
            busy = false
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current

    var notificationAccessGranted by remember { mutableStateOf(false) }
    var smsGranted by remember { mutableStateOf(false) }

    fun refreshPermissionState() {
        notificationAccessGranted = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Re-check whenever the user comes back from system settings.
    LifecycleResumeEffect(Unit) {
        refreshPermissionState()
        onPauseOrDispose { }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionState() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Capture channels", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

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
                                android.net.Uri.fromParts("package", context.packageName, null),
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

        Text("Ledger", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = viewModel.defaultCurrency,
                onValueChange = { viewModel.defaultCurrency = it.uppercase().take(3) },
                label = { Text("Default currency") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = viewModel::saveDefaultCurrency) { Text("Save") }
        }
        OutlinedButton(onClick = viewModel::refreshRates, enabled = !viewModel.busy) {
            Text("Refresh Forex rates now")
        }

        Text("Binance (read-only)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            if (viewModel.hasBinanceKeys) {
                "API keys are configured and stored encrypted. Only read endpoints are ever called — " +
                    "create the key with trading and withdrawals disabled."
            } else {
                "Paste a READ-ONLY API key pair. Create it in Binance with trading and " +
                    "withdrawals disabled; it is stored encrypted on-device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = viewModel.apiKeyInput,
            onValueChange = { viewModel.apiKeyInput = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = viewModel.secretKeyInput,
            onValueChange = { viewModel.secretKeyInput = it },
            label = { Text("Secret key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = viewModel::saveBinanceKeys,
                enabled = viewModel.apiKeyInput.isNotBlank() && viewModel.secretKeyInput.isNotBlank(),
            ) { Text("Save keys") }
            OutlinedButton(
                onClick = viewModel::testBinanceConnection,
                enabled = viewModel.hasBinanceKeys && !viewModel.busy,
            ) { Text("Test & sync") }
            if (viewModel.hasBinanceKeys) {
                OutlinedButton(onClick = viewModel::clearBinanceKeys) { Text("Remove") }
            }
        }

        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (granted) LedgerColors.credit else MaterialTheme.colorScheme.error,
                )
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                OutlinedButton(onClick = onClick) { Text(buttonLabel) }
            }
        }
    }
}
