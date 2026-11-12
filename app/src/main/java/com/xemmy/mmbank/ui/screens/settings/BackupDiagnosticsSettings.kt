package com.xemmy.mmbank.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.xemmy.mmbank.data.backup.BackupPasswordRules
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.data.ai.AiCallLogStore
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.ai.AiEndpointNormalizer
import com.xemmy.mmbank.domain.ai.AiProviderMode
import com.xemmy.mmbank.domain.ai.AiResponseParser
import com.xemmy.mmbank.domain.ai.AiTransactionPrompt
import com.xemmy.mmbank.domain.ai.LoopbackGuard
import com.xemmy.mmbank.domain.model.BackgroundStyle
import com.xemmy.mmbank.domain.people.PersonCounts
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.util.CaptureAccess
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import com.xemmy.mmbank.ui.screens.budgets.CategoriesTabContent
import com.xemmy.mmbank.ui.screens.budgets.CategoriesViewModel
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.theme.displayName
import com.xemmy.mmbank.ui.util.asMoney
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Subpage: Backup & restore
// ---------------------------------------------------------------------------

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Backup & restore", onBack = onBack) {
        BackupSection(viewModel)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        RetentionSection(viewModel)
        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// Subpage: Diagnostics
// ---------------------------------------------------------------------------

@Composable
fun DiagnosticsSettingsScreen(onBack: () -> Unit) {
    SettingsSubpage(title = "Diagnostics", onBack = onBack) {
        DiagnosticsSection()
    }
}

// ---------------------------------------------------------------------------
// Shared pieces reused by the subpages
// ---------------------------------------------------------------------------

@Composable
private fun BackupSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup { bytes ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Couldn't open the selected file")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importBackup {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Couldn't read the selected file")
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv { text ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("Couldn't open the selected file")
            }
        }
    }

    Text(
        "Exports accounts, transactions, custom rules, sender names and settings into one " +
            "AES-256 encrypted file protected by the password below. Binance keys are never " +
            "exported. Importing REPLACES the app's current data.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = viewModel.backupPassword,
        onValueChange = { viewModel.backupPassword = it },
        label = { Text("Backup password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = viewModel.backupPasswordConfirm,
        onValueChange = { viewModel.backupPasswordConfirm = it },
        label = { Text("Confirm password (for export)") },
        singleLine = true,
        isError = BackupPasswordRules.mismatch(viewModel.backupPassword, viewModel.backupPasswordConfirm),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    var confirmImport by remember { mutableStateOf(false) }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "Importing wipes every account, transaction and setting currently in the app " +
                        "and replaces them with the backup. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importLauncher.launch(arrayOf("*/*"))
                }) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("mmbank-backup-$stamp.mmb")
            },
            enabled = BackupPasswordRules.canExport(viewModel.backupPassword, viewModel.backupPasswordConfirm) &&
                !viewModel.busy,
        ) { Text("Export") }
        OutlinedButton(
            onClick = { confirmImport = true },
            enabled = BackupPasswordRules.canImport(viewModel.backupPassword) && !viewModel.busy,
        ) { Text("Import") }
    }
    if (viewModel.backupPassword.isNotEmpty() && viewModel.backupPassword.length < 6) {
        Text(
            "Password must be at least 6 characters.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        "Or export a plain CSV of every transaction for spreadsheets (date, amount, " +
            "merchant, category, account, person…). This file is NOT encrypted or " +
            "password-protected.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            csvLauncher.launch("mmbank-$stamp.csv")
        },
        enabled = !viewModel.busy,
    ) { Text("Export CSV") }
}

/**
 * Opt-in weekly prune of old, unremarkable transaction history (see
 * [com.xemmy.mmbank.data.retention.TransactionRetentionWorker]). Placed next
 * to backups since both are about how much history the ledger carries.
 * Off by default -- picking "Forever" cancels the schedule entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionSection(viewModel: SettingsViewModel) {
    Text("Transaction history", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "Prunes old debits automatically, weekly, on-device. Income, unusually large " +
            "amounts, and anything inside the window below are always kept, regardless " +
            "of age. Back up first if you want to keep everything.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TRANSACTION_RETENTION_OPTIONS_MONTHS.forEach { months ->
            FilterChip(
                selected = viewModel.transactionRetentionMonths == months,
                onClick = { viewModel.updateTransactionRetentionMonths(months) },
                label = { Text(transactionRetentionLabel(months)) },
            )
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshRetentionPreview() }
    viewModel.retentionPreview?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Shows what [com.xemmy.mmbank.diagnostics.Diagnostics] has recorded: the last
 * fatal (uncaught) crash, and any non-fatal errors that a screen caught and
 * recovered from instead of crashing. Lets a crash be reported by screenshot
 * or copy-paste instead of requiring adb/logcat.
 */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    var crashText by remember { mutableStateOf<String?>(null) }
    var errorsText by remember { mutableStateOf<String?>(null) }
    var captureText by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LifecycleResumeEffect(refreshKey) {
        crashText = Diagnostics.readCrash(context)
        errorsText = Diagnostics.readErrors(context)
        captureText = Diagnostics.readCaptureLog(context)
        onPauseOrDispose { }
    }

    if (crashText == null && errorsText == null && captureText == null) {
        Text(
            "Nothing recorded. If the app ever crashes or a screen fails to load, the details will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    crashText?.let {
        DiagnosticsLogCard(
            title = "Last crash",
            text = it,
            onClear = {
                Diagnostics.clearCrash(context)
                refreshKey++
            },
        )
    }
    captureText?.let {
        DiagnosticsLogCard(
            title = "Capture log (newest first: saved, duplicate, ignored, unmatched, failed)",
            text = it,
            onClear = {
                Diagnostics.clearCaptureLog(context)
                refreshKey++
            },
        )
    }
    errorsText?.let {
        DiagnosticsLogCard(
            title = "Recent recovered errors",
            text = it,
            onClear = {
                Diagnostics.clearErrors(context)
                refreshKey++
            },
        )
    }
}

@Composable
internal fun DiagnosticsLogCard(title: String, text: String, onClear: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share log"))
                }) { Text("Share") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
internal fun PermissionCard(
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

// ---------------------------------------------------------------------------
// Permission checks (also drive the root menu's status subtitles)
// ---------------------------------------------------------------------------

internal fun isNotificationAccessGranted(context: Context): Boolean = CaptureAccess.notificationAccess(context)

internal fun isSmsGranted(context: Context): Boolean = CaptureAccess.sms(context)

internal fun isBatteryExempt(context: Context): Boolean = CaptureAccess.batteryExempt(context)
