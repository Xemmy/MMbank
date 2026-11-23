package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider

// ---------------------------------------------------------------------------
// Subpage: Binance
// ---------------------------------------------------------------------------

@Composable
fun BinanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Binance (read-only)", onBack = onBack) {
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
