package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.data.ai.AiCallLogStore
import com.xemmy.mmbank.domain.ai.AiPrivacyStatus
import com.xemmy.mmbank.domain.ai.AiProviderMode
import com.xemmy.mmbank.domain.ai.LoopbackGuard
import com.xemmy.mmbank.ui.AppViewModelProvider

// ---------------------------------------------------------------------------
// Subpage: AI parsing (Gate 2 — local loopback-only server, or an online provider)
// ---------------------------------------------------------------------------

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAiState()
        onPauseOrDispose { }
    }
    val isLocal = viewModel.aiProviderMode == AiProviderMode.LOCAL
    val loopbackValid = LoopbackGuard.isLoopbackUrl(viewModel.aiEndpointUrl.trim())
    val endpointValid = if (isLocal) loopbackValid else viewModel.aiEndpointUrl.isNotBlank()

    val context = LocalContext.current
    var aiLogText by remember { mutableStateOf<String?>(null) }
    var aiLogCount by remember { mutableStateOf(0) }
    var aiLogRefreshKey by remember { mutableStateOf(0) }
    LifecycleResumeEffect(aiLogRefreshKey) {
        aiLogText = AiCallLogStore.readLog(context)
        aiLogCount = AiCallLogStore.callCount(context)
        onPauseOrDispose { }
    }
    // Every change to the test-message status (including the final result) means
    // a request just completed, so re-read the log to show the new entry
    // without waiting for the screen to pause/resume.
    LaunchedEffect(viewModel.aiTestMessageStatus) {
        if (viewModel.aiTestMessageStatus != null) {
            aiLogText = AiCallLogStore.readLog(context)
            aiLogCount = AiCallLogStore.callCount(context)
        }
    }

    SettingsSubpage(title = "AI parsing", onBack = onBack) {
        if (AiPrivacyStatus.sendsTextOffDevice(viewModel.aiParsingEnabled, viewModel.aiProviderMode)) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        AiPrivacyStatus.ONLINE_BANNER_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        AiPrivacyStatus.ONLINE_BANNER_BODY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Text(
            "When the built-in rules can't read a bank message, MMbank can ask an AI to extract the " +
                "transaction. AI suggestions never write to the ledger directly — they appear as disabled " +
                "rules you approve under Parsing rules.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use AI parsing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off by default. Runs only on messages the built-in rules miss.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.aiParsingEnabled,
                onCheckedChange = viewModel::updateAiParsingEnabled,
            )
        }

        OutlinedTextField(
            value = viewModel.aiDailyRequestLimitInput,
            onValueChange = { viewModel.aiDailyRequestLimitInput = it.filter(Char::isDigit) },
            label = { Text("Daily automatic-parsing limit") },
            supportingText = {
                Text(
                    "Used today: ${viewModel.aiRequestsUsedToday} / ${viewModel.aiDailyRequestLimit}. " +
                        "Caps background parsing only — manual \"Parse with AI\" and the test button below " +
                        "are never limited.",
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = viewModel::saveAiDailyRequestLimit) { Text("Save limit") }
        }

        Text("Provider", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isLocal,
                onClick = { viewModel.updateAiProviderMode(AiProviderMode.LOCAL) },
                label = { Text("On-device (local)") },
            )
            FilterChip(
                selected = !isLocal,
                onClick = { viewModel.updateAiProviderMode(AiProviderMode.ONLINE) },
                label = { Text("Online (cloud API)") },
            )
        }

        if (isLocal) {
            Text(
                "On-device only: MMbank calls a local AI app (e.g. Off Grid) running on this phone. " +
                    "It only ever reaches a loopback address — never the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "⚠ Sends message text to a third party: the raw bank/payment notification is sent over " +
                    "the internet to whatever endpoint you configure below. Only enable this if you trust " +
                    "that provider with your financial data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = viewModel.aiEndpointUrl,
            onValueChange = { viewModel.aiEndpointUrl = it },
            label = { Text("Server endpoint") },
            supportingText = {
                Text(
                    if (isLocal) {
                        if (loopbackValid) {
                            "On-device only: 127.0.0.1 or localhost, e.g. http://127.0.0.1:11434/v1/chat/completions"
                        } else {
                            "Not a loopback address — only 127.0.0.1 / localhost is allowed for the on-device mode."
                        }
                    } else {
                        "OpenAI-compatible /v1/chat/completions URL, e.g. https://api.openai.com/v1/chat/completions"
                    },
                )
            },
            isError = !endpointValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.aiModelName,
            onValueChange = { viewModel.aiModelName = it },
            label = { Text(if (isLocal) "Model (optional)" else "Model") },
            supportingText = {
                Text(
                    if (isLocal) {
                        "Leave blank to use the server's currently loaded model."
                    } else {
                        "Required by most cloud providers, e.g. gpt-4o-mini."
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!isLocal) {
            OutlinedTextField(
                value = viewModel.aiApiKeyInput,
                onValueChange = { viewModel.aiApiKeyInput = it },
                label = { Text("API key") },
                supportingText = { Text("Stored encrypted on this device (Android Keystore), sent only to the endpoint above.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::saveAiApiKey) { Text("Save key") }
                TextButton(onClick = viewModel::clearAiApiKey) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::saveAiEndpoint) { Text("Save") }
            OutlinedButton(onClick = viewModel::testAiConnection, enabled = !viewModel.aiTesting) {
                Text(if (viewModel.aiTesting) "Testing…" else "Test connection")
            }
        }

        viewModel.aiTestStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Request log", style = MaterialTheme.typography.labelLarge)
        Text(
            "$aiLogCount AI request${if (aiLogCount == 1) "" else "s"} made on this device so far.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = viewModel::sendTestAiMessage,
                enabled = !viewModel.sendingTestAiMessage,
            ) {
                Text(if (viewModel.sendingTestAiMessage) "Sending…" else "Send test message")
            }
        }
        Text(
            "Sends an obviously-fake sample transaction to the configured engine so you can see a " +
                "request appear below without waiting for a real one. Never written to the ledger or rules.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        viewModel.aiTestMessageStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        val logText = aiLogText
        if (logText.isNullOrBlank()) {
            Text(
                "No requests logged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DiagnosticsLogCard(
                title = "Recent requests (newest first)",
                text = logText,
                onClear = {
                    AiCallLogStore.clearLog(context)
                    aiLogRefreshKey++
                },
            )
        }
    }
}
