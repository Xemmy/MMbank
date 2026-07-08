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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var defaultCurrency by mutableStateOf(container.preferences.defaultCurrency)
    var hideZeroBalances by mutableStateOf(container.preferences.hideZeroBalances)
        private set
    var apiKeyInput by mutableStateOf("")
    var secretKeyInput by mutableStateOf("")
    var hasBinanceKeys by mutableStateOf(container.credentialStore.hasBinanceCredentials)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    var defaultPerson by mutableStateOf(container.preferences.defaultPerson.orEmpty())

    /** Saved people list — the person menu's source, editable here, exported in backups. */
    var persons by mutableStateOf(container.preferences.persons)
        private set

    /** Names still on transactions but absent from the saved list (old data / deletions). */
    var unlistedPersons by mutableStateOf<List<String>>(emptyList())
        private set
    private var transactionPersons: List<String> = emptyList()

    init {
        viewModelScope.launch {
            container.transactionRepository.observeTransactions()
                .catch { emit(emptyList()) }
                .collect { txs ->
                    transactionPersons = txs.mapNotNull { it.person?.trim() }
                        .filter { it.isNotEmpty() }
                        .distinctBy { it.lowercase() }
                    recomputeUnlisted()
                }
        }
    }

    private fun recomputeUnlisted() {
        unlistedPersons = transactionPersons
            .filter { p -> persons.none { it.equals(p, ignoreCase = true) } }
            .sorted()
    }

    fun addPersonToList(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        container.preferences.addPerson(clean)
        persons = container.preferences.persons
        recomputeUnlisted()
    }

    fun removePersonFromList(name: String) {
        container.preferences.persons = container.preferences.persons - name
        if (container.preferences.defaultPerson == name) {
            container.preferences.defaultPerson = null
            defaultPerson = ""
        }
        persons = container.preferences.persons
        recomputeUnlisted()
        statusMessage = "$name removed from the list"
    }

    /**
     * Renames on the saved list AND on every transaction carrying the old
     * name. Also used to merge an old transaction-only name into a listed one.
     */
    fun renamePersonEverywhere(from: String, to: String) {
        val clean = to.trim()
        if (busy || clean.isEmpty() || clean == from) return
        viewModelScope.launch {
            busy = true
            runCatching {
                container.transactionRepository.renamePerson(from, clean)
                container.preferences.persons =
                    container.preferences.persons.map { if (it == from) clean else it }
                container.preferences.addPerson(clean)
                if (container.preferences.defaultPerson == from) {
                    container.preferences.defaultPerson = clean
                    defaultPerson = clean
                }
                persons = container.preferences.persons
                recomputeUnlisted()
            }.onSuccess {
                statusMessage = "Renamed $from to $clean everywhere"
            }.onFailure {
                container.recordError("SettingsViewModel.renamePerson", it)
                statusMessage = "Rename failed: ${it.message}"
            }
            busy = false
        }
    }

    /** Strips [name] off every transaction (they become unassigned). */
    fun clearPersonFromTransactions(name: String) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            runCatching { container.transactionRepository.renamePerson(name, null) }
                .onSuccess { statusMessage = "Removed $name from all transactions" }
                .onFailure {
                    container.recordError("SettingsViewModel.clearPerson", it)
                    statusMessage = "Failed: ${it.message}"
                }
            busy = false
        }
    }

    fun saveDefaultPerson() {
        container.preferences.defaultPerson = defaultPerson
        defaultPerson = container.preferences.defaultPerson.orEmpty()
        statusMessage = if (defaultPerson.isBlank()) {
            "New transactions start unassigned"
        } else {
            "New transactions will be assigned to $defaultPerson"
        }
    }

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

    fun setHideZeroBalances(hide: Boolean) {
        container.preferences.hideZeroBalances = hide
        hideZeroBalances = hide
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
                .onSuccess {
                    val failed = container.binanceWalletAssets.lastFailedWallets
                    statusMessage = if (failed.isEmpty()) {
                        "Connected — $it holdings synced (Spot · Funding · Earn)"
                    } else {
                        "Synced $it holdings, but couldn't read: ${failed.joinToString(", ")}. " +
                            "Enable those permissions on the API key."
                    }
                }
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

    var backupPassword by mutableStateOf("")

    /** [writeBytes] runs on IO and streams the encrypted blob to the chosen document. */
    fun exportBackup(writeBytes: suspend (ByteArray) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Encrypting backup…"
            runCatching {
                val blob = container.backupManager.export(backupPassword.toCharArray())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeBytes(blob) }
            }.onSuccess {
                statusMessage = "Backup exported (encrypted)"
            }.onFailure {
                statusMessage = "Export failed: ${it.message}"
            }
            busy = false
        }
    }

    /** [readBytes] runs on IO and loads the chosen document. Import REPLACES current data. */
    fun importBackup(readBytes: suspend () -> ByteArray) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Restoring backup…"
            runCatching {
                val blob = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readBytes() }
                container.backupManager.import(blob, backupPassword.toCharArray())
            }.onSuccess { (accounts, transactions) ->
                defaultCurrency = container.preferences.defaultCurrency
                statusMessage = "Backup restored: $accounts accounts, $transactions transactions"
            }.onFailure {
                statusMessage = "Import failed: ${it.message}"
            }
            busy = false
        }
    }
}

// ---------------------------------------------------------------------------
// Root: grouped menu, modern app style — each row opens a focused subpage.
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    onOpen: (String) -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    var captureOk by remember { mutableStateOf(true) }
    var crashRecorded by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        captureOk = isNotificationAccessGranted(context) && isSmsGranted(context) && isBatteryExempt(context)
        crashRecorded = Diagnostics.readCrash(context) != null
        onPauseOrDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        MenuSectionLabel("Capture")
        Card(Modifier.fillMaxWidth()) {
            MenuRow(
                icon = Icons.Filled.NotificationsActive,
                title = "Capture & permissions",
                subtitle = if (captureOk) "All channels enabled" else "Action needed",
                subtitleColor = if (captureOk) LedgerColors.credit else MaterialTheme.colorScheme.error,
                onClick = { onOpen("settings_capture") },
            )
        }

        MenuSectionLabel("General")
        Card(Modifier.fillMaxWidth()) {
            Column {
                MenuRow(
                    icon = Icons.Filled.Tune,
                    title = "Preferences",
                    subtitle = "Currency ${viewModel.defaultCurrency}" +
                        (viewModel.defaultPerson.takeIf { it.isNotBlank() }?.let { " · default person $it" } ?: "") +
                        " · Forex rates",
                    onClick = { onOpen("settings_prefs") },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.CurrencyBitcoin,
                    title = "Binance",
                    subtitle = if (viewModel.hasBinanceKeys) "Connected (read-only keys saved)" else "Not configured",
                    onClick = { onOpen("settings_binance") },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.Rule,
                    title = "Parsing rules",
                    subtitle = "Teach the app new bank message formats",
                    onClick = { onOpen("rules") },
                )
            }
        }

        MenuSectionLabel("Data")
        Card(Modifier.fillMaxWidth()) {
            Column {
                MenuRow(
                    icon = Icons.Filled.Backup,
                    title = "Backup & restore",
                    subtitle = "Encrypted export and import",
                    onClick = { onOpen("settings_backup") },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.BugReport,
                    title = "Diagnostics",
                    subtitle = if (crashRecorded) "Crash log recorded — tap to view" else "No problems recorded",
                    subtitleColor = if (crashRecorded) MaterialTheme.colorScheme.error else null,
                    onClick = { onOpen("settings_diagnostics") },
                )
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleColor: Color? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared subpage chrome: back arrow + title, then scrollable content. */
@Composable
private fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

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

// ---------------------------------------------------------------------------
// Subpage: Preferences (currency, default person, Forex)
// ---------------------------------------------------------------------------

@Composable
fun PreferencesSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Preferences", onBack = onBack) {
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = viewModel.defaultPerson,
                onValueChange = { viewModel.defaultPerson = it },
                label = { Text("Default person for new transactions") },
                supportingText = { Text("Leave empty for unassigned. Change anyone later per transaction.") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = viewModel::saveDefaultPerson) { Text("Save") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide zero-value coins/tokens", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Dust and unpriced crypto holdings are left off the Dashboard. Turn off to show every holding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.hideZeroBalances,
                onCheckedChange = viewModel::setHideZeroBalances,
            )
        }

        OutlinedButton(onClick = viewModel::refreshRates, enabled = !viewModel.busy) {
            Text("Refresh Forex rates now")
        }

        PeopleSection(viewModel)

        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Editable saved-people list: add, rename (updates every transaction), remove. */
@Composable
private fun PeopleSection(viewModel: SettingsViewModel) {
    var newPerson by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }

    Text("People", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "This list feeds the person menu on transactions and is included in backups. " +
            "Renaming updates every transaction that carries the old name.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (viewModel.persons.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                viewModel.persons.forEachIndexed { index, person ->
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            person,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { renaming = person }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename $person",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.removePersonFromList(person) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove $person",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < viewModel.persons.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = newPerson,
            onValueChange = { newPerson = it },
            label = { Text("Add a person") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = newPerson.isNotBlank(),
            onClick = {
                viewModel.addPersonToList(newPerson)
                newPerson = ""
            },
        ) { Text("Add") }
    }

    if (viewModel.unlistedPersons.isNotEmpty()) {
        Text(
            "On transactions but not in your list",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            "Old names still attached to transactions. Add one back to the list (+), " +
                "merge/rename it into another name (pencil — updates those transactions), " +
                "or clear it off all transactions (bin).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column {
                viewModel.unlistedPersons.forEachIndexed { index, person ->
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            person,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.addPersonToList(person) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add $person to the list",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { renaming = person }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Merge or rename $person",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.clearPersonFromTransactions(person) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Clear $person from all transactions",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < viewModel.unlistedPersons.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    renaming?.let { oldName ->
        var newName by remember(oldName) { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename person") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    supportingText = { Text("Every transaction assigned to “$oldName” will be updated.") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != oldName,
                    onClick = {
                        viewModel.renamePersonEverywhere(oldName, newName)
                        renaming = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("mmbank-backup-$stamp.mmb")
            },
            enabled = viewModel.backupPassword.length >= 6 && !viewModel.busy,
        ) { Text("Export") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = viewModel.backupPassword.length >= 6 && !viewModel.busy,
        ) { Text("Import") }
    }
    if (viewModel.backupPassword.isNotEmpty() && viewModel.backupPassword.length < 6) {
        Text(
            "Password must be at least 6 characters.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
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
    var refreshKey by remember { mutableStateOf(0) }

    LifecycleResumeEffect(refreshKey) {
        crashText = Diagnostics.readCrash(context)
        errorsText = Diagnostics.readErrors(context)
        onPauseOrDispose { }
    }

    if (crashText == null && errorsText == null) {
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
private fun DiagnosticsLogCard(title: String, text: String, onClear: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
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

// ---------------------------------------------------------------------------
// Permission checks (also drive the root menu's status subtitles)
// ---------------------------------------------------------------------------

private fun isNotificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun isSmsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

private fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
