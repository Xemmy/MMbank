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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Science
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
import com.xemmy.mmbank.domain.model.DesignMode
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import com.xemmy.mmbank.ui.theme.LedgerColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Obviously-fake sample used by the "Send test message" button -- never persisted, only logged. */
private const val TEST_AI_MESSAGE = "TEST MESSAGE — You spent AED 42.00 at Test Merchant. Your available balance is now AED 958.00."

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var defaultCurrency by mutableStateOf(container.preferences.defaultCurrency)
    var hideZeroBalances by mutableStateOf(container.preferences.hideZeroBalances)
        private set
    var hideCryptoBalance by mutableStateOf(container.preferences.hideCryptoBalance)
        private set
    var excludeCryptoFromTotal by mutableStateOf(container.preferences.excludeCryptoFromTotal)
        private set
    var hideCreditCardBalance by mutableStateOf(container.preferences.hideCreditCardBalance)
        private set
    var excludeCreditCardFromTotal by mutableStateOf(container.preferences.excludeCreditCardFromTotal)
        private set
    var appLockEnabled by mutableStateOf(container.preferences.appLockEnabled)
        private set
    var appLockTimeoutMinutes by mutableStateOf(container.preferences.appLockTimeoutMinutes)
        private set
    var demoModeEnabled by mutableStateOf(container.preferences.demoModeEnabled)
        private set

    // Classic vs Premium: a reactive flow (not a mutableStateOf snapshot) since
    // the root theme wrapper, the AI-parsing indicator and the transaction
    // detail dialog all need to see a toggle here live, not just this screen.
    val designMode: StateFlow<DesignMode> = container.designMode
    fun setDesignMode(mode: DesignMode) = container.setDesignMode(mode)

    // --- Gate-2 AI parsing: local (loopback-only, e.g. Off Grid) or an explicit online provider ---
    var aiParsingEnabled by mutableStateOf(container.preferences.aiParsingEnabled)
        private set
    var aiProviderMode by mutableStateOf(container.preferences.aiProviderMode)
        private set
    var aiDailyRequestLimit by mutableStateOf(container.preferences.aiDailyRequestLimit)
        private set
    var aiDailyRequestLimitInput by mutableStateOf(container.preferences.aiDailyRequestLimit.toString())
    var aiEndpointUrl by mutableStateOf(container.preferences.aiEndpointUrl)
    var aiModelName by mutableStateOf(container.preferences.aiModelName)
    var aiApiKeyInput by mutableStateOf(container.credentialStore.aiApiKey.orEmpty())
    var aiTestStatus by mutableStateOf<String?>(null)
        private set
    var aiTesting by mutableStateOf(false)
        private set
    var aiTestMessageStatus by mutableStateOf<String?>(null)
        private set
    var sendingTestAiMessage by mutableStateOf(false)
        private set
    var aiRequestsUsedToday by mutableStateOf(container.aiRequestsUsedToday())
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

    fun updateHideZeroBalances(hide: Boolean) {
        container.preferences.hideZeroBalances = hide
        hideZeroBalances = hide
    }

    fun updateHideCryptoBalance(hide: Boolean) {
        container.preferences.hideCryptoBalance = hide
        hideCryptoBalance = hide
    }

    fun updateExcludeCryptoFromTotal(exclude: Boolean) {
        container.preferences.excludeCryptoFromTotal = exclude
        excludeCryptoFromTotal = exclude
    }

    fun updateHideCreditCardBalance(hide: Boolean) {
        container.preferences.hideCreditCardBalance = hide
        hideCreditCardBalance = hide
    }

    fun updateExcludeCreditCardFromTotal(exclude: Boolean) {
        container.preferences.excludeCreditCardFromTotal = exclude
        excludeCreditCardFromTotal = exclude
    }

    /** Call only after the enabling auth prompt has succeeded (the UI enforces that). */
    fun updateAppLockEnabled(enabled: Boolean) {
        container.setAppLockEnabled(enabled)
        appLockEnabled = enabled
    }

    fun updateAppLockTimeout(minutes: Int) {
        container.setAppLockTimeoutMinutes(minutes)
        appLockTimeoutMinutes = minutes
    }

    /**
     * Re-snapshots the app-lock prefs. The root Settings menu and the Security
     * subpage are separate nav destinations with separate SettingsViewModel
     * instances, so the menu's subtitle must refresh on resume after the
     * subpage changed the underlying preference.
     */
    fun refreshAppLockState() {
        appLockEnabled = container.preferences.appLockEnabled
        appLockTimeoutMinutes = container.preferences.appLockTimeoutMinutes
    }

    /** Re-snapshots the Demo mode flag (separate VM instances per nav destination). */
    fun refreshDemoState() {
        demoModeEnabled = container.preferences.demoModeEnabled
    }

    // --- AI parsing settings (local loopback, or an explicit online provider) ---

    fun updateAiParsingEnabled(enabled: Boolean) {
        container.preferences.aiParsingEnabled = enabled
        aiParsingEnabled = enabled
    }

    /**
     * Switching modes clears any Test-connection status from the other mode
     * (a stale "Connected" from Local shouldn't linger once Online is picked,
     * or vice versa) but never touches the saved endpoint/model/key fields.
     */
    fun updateAiProviderMode(mode: AiProviderMode) {
        container.preferences.aiProviderMode = mode
        aiProviderMode = mode
        aiTestStatus = null
    }

    /**
     * Persists the daily cap on automatic Gate-2 escalations (background
     * parsing only -- manual "Parse with AI" and the test buttons below stay
     * uncapped). Invalid/blank input is ignored, leaving the field showing
     * the last saved value rather than silently zeroing the limit.
     */
    fun saveAiDailyRequestLimit() {
        val limit = aiDailyRequestLimitInput.toIntOrNull()
        if (limit == null || limit < 1) {
            aiDailyRequestLimitInput = aiDailyRequestLimit.toString()
            return
        }
        container.preferences.aiDailyRequestLimit = limit
        aiDailyRequestLimit = container.preferences.aiDailyRequestLimit
        aiDailyRequestLimitInput = aiDailyRequestLimit.toString()
    }

    /** Persists the endpoint + model fields (mode-appropriate validation happens at call time by the engine). */
    fun saveAiEndpoint() {
        // Normalize at save time (not request time) so the corrected URL is
        // visible in the field: completes base URLs to /chat/completions and
        // rewrites Google's native Gemini path -- which rejects Bearer auth
        // with a bare 401 -- to Google's OpenAI-compatibility endpoint.
        val normalized = AiEndpointNormalizer.normalize(aiEndpointUrl)
        val corrected = normalized != aiEndpointUrl.trim().trimEnd('/')
        container.preferences.aiEndpointUrl = normalized
        container.preferences.aiModelName = aiModelName.trim()
        aiEndpointUrl = container.preferences.aiEndpointUrl
        aiModelName = container.preferences.aiModelName
        aiTestStatus = if (corrected) "Saved — endpoint completed to $normalized" else "Saved"
    }

    /** Persists the online-provider API key, encrypted (SecureCredentialStore), never in plain prefs. */
    fun saveAiApiKey() {
        container.credentialStore.aiApiKey = aiApiKeyInput.trim()
        aiApiKeyInput = container.credentialStore.aiApiKey.orEmpty()
        aiTestStatus = "Saved"
    }

    fun clearAiApiKey() {
        container.credentialStore.clearAiApiKey()
        aiApiKeyInput = ""
    }

    /** Re-snapshots AI settings (separate VM instances per nav destination). */
    fun refreshAiState() {
        aiParsingEnabled = container.preferences.aiParsingEnabled
        aiProviderMode = container.preferences.aiProviderMode
        aiDailyRequestLimit = container.preferences.aiDailyRequestLimit
        aiDailyRequestLimitInput = aiDailyRequestLimit.toString()
        aiRequestsUsedToday = container.aiRequestsUsedToday()
        aiEndpointUrl = container.preferences.aiEndpointUrl
        aiModelName = container.preferences.aiModelName
        aiApiKeyInput = container.credentialStore.aiApiKey.orEmpty()
    }

    /** Saves the current fields (+ key, if online), then probes the configured server and reports the result. */
    fun testAiConnection() {
        if (aiTesting) return
        saveAiEndpoint()
        if (aiProviderMode == AiProviderMode.ONLINE) saveAiApiKey()
        viewModelScope.launch {
            aiTesting = true
            aiTestStatus = "Testing…"
            aiTestStatus = container.localAiManager.testConnection().fold(
                onSuccess = { "Connected — AI server is reachable" },
                onFailure = { "Failed: ${it.message ?: "unreachable"}" },
            )
            aiTesting = false
        }
    }

    /**
     * Sends a canned, obviously-fake bank message through the real configured
     * engine (local or online) and its logging wrapper, so the request/response
     * log below can be exercised without waiting for a genuine unparsed
     * notification. Ignores the enabled flag, like [testAiConnection] --
     * lets a provider be tried before AI parsing is switched on. Deliberately
     * calls the engine directly rather than routing through the Gate-2
     * self-healing hook: this must only ever produce a log entry, never a
     * pending rule or a ledger write.
     */
    fun sendTestAiMessage() {
        if (sendingTestAiMessage) return
        saveAiEndpoint()
        if (aiProviderMode == AiProviderMode.ONLINE) saveAiApiKey()
        viewModelScope.launch {
            sendingTestAiMessage = true
            aiTestMessageStatus = "Sending…"
            val prompt = AiTransactionPrompt.build(TEST_AI_MESSAGE)
            aiTestMessageStatus = container.localAiManager.testGenerate(prompt).fold(
                onSuccess = { raw ->
                    val parsed = AiResponseParser.parse(raw)
                    if (parsed?.transactionIntent == true) {
                        "Test sent — parsed ${parsed.currency ?: ""} ${parsed.amount ?: ""} at " +
                            "${parsed.merchant ?: "?"}. See the log below."
                    } else {
                        "Test sent — response logged below, but it didn't parse as a transaction."
                    }
                },
                onFailure = { "Test failed: ${it.message ?: "unknown error"}" },
            )
            sendingTestAiMessage = false
        }
    }

    /**
     * Turns Demo mode ON — real data hides, a fresh sample batch appears. Call
     * only after the enabling auth prompt has succeeded (the UI enforces that).
     */
    fun enableDemoMode() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Loading demo data…"
            runCatching { container.enableDemoMode() }
                .onSuccess {
                    demoModeEnabled = true
                    persons = container.preferences.persons
                    recomputeUnlisted()
                    statusMessage = "Demo mode on — your real data is hidden"
                }
                .onFailure {
                    container.recordError("SettingsViewModel.enableDemoMode", it)
                    statusMessage = "Couldn't start demo mode: ${it.message}"
                }
            busy = false
        }
    }

    /**
     * Turns Demo mode OFF — deletes the sample data and restores the real
     * ledger, rules and people. Call only after the biometric prompt has
     * succeeded (revealing real data is the guarded step; the UI enforces it).
     */
    fun disableDemoMode() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Removing demo data…"
            runCatching { container.disableDemoMode() }
                .onSuccess {
                    demoModeEnabled = false
                    persons = container.preferences.persons
                    recomputeUnlisted()
                    statusMessage = "Demo mode off — your real data is back"
                }
                .onFailure {
                    container.recordError("SettingsViewModel.disableDemoMode", it)
                    statusMessage = "Couldn't exit demo mode: ${it.message}"
                }
            busy = false
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

    /** [writeText] runs on IO and streams the unencrypted CSV to the chosen document. */
    fun exportCsv(writeText: suspend (String) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            statusMessage = "Exporting CSV…"
            runCatching {
                val csv = container.backupManager.exportCsv()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeText(csv) }
            }.onSuccess {
                statusMessage = "Transactions exported to CSV"
            }.onFailure {
                statusMessage = "CSV export failed: ${it.message}"
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
                // Import clears every table (demo rows included) and restores real
                // data, so Demo mode must not stay on pointing at an empty partition.
                container.forceDemoModeOff()
                demoModeEnabled = false
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
    val designMode by viewModel.designMode.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        captureOk = isNotificationAccessGranted(context) && isSmsGranted(context) && isBatteryExempt(context)
        crashRecorded = Diagnostics.readCrash(context) != null
        viewModel.refreshAppLockState()
        viewModel.refreshDemoState()
        viewModel.refreshAiState()
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
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Local AI parsing",
                    subtitle = if (viewModel.aiParsingEnabled) "On · on-device server" else "Off · read messages the rules miss",
                    onClick = { onOpen("settings_ai") },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.Palette,
                    title = "Design",
                    subtitle = if (designMode == DesignMode.PREMIUM) "Premium · Material 3 Expressive" else "Classic",
                    onClick = { onOpen("settings_design") },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.Lock,
                    title = "Security",
                    subtitle = if (viewModel.appLockEnabled) {
                        "App lock on · auto-lock ${appLockTimeoutLabel(viewModel.appLockTimeoutMinutes)}"
                    } else {
                        "Biometric app lock (off)"
                    },
                    onClick = { onOpen("settings_security") },
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
                HorizontalDivider(Modifier.padding(start = 56.dp))
                MenuRow(
                    icon = Icons.Filled.Science,
                    title = "Demo mode",
                    subtitle = if (viewModel.demoModeEnabled) {
                        "On — real data hidden behind sample data"
                    } else {
                        "Show sample data for demos (your data stays safe)"
                    },
                    subtitleColor = if (viewModel.demoModeEnabled) MaterialTheme.colorScheme.primary else null,
                    onClick = { onOpen("settings_demo") },
                )
            }
        }

        // App version, read from the package at runtime (no BuildConfig needed).
        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "—"
        }
        Text(
            "Version $versionName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp),
        )
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
                onCheckedChange = viewModel::updateHideZeroBalances,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide crypto balance", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Removes the Crypto (Binance) section from the Dashboard entirely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.hideCryptoBalance,
                onCheckedChange = viewModel::updateHideCryptoBalance,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Exclude crypto from total balance", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Net worth on the Dashboard and Insights won't count crypto's value. " +
                        "Independent of the toggle above — crypto can stay visible while excluded, or vice versa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.excludeCryptoFromTotal,
                onCheckedChange = viewModel::updateExcludeCryptoFromTotal,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide credit card balance", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Removes credit card accounts from the Dashboard's Accounts list entirely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.hideCreditCardBalance,
                onCheckedChange = viewModel::updateHideCreditCardBalance,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Exclude credit card from total balance", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Net worth on the Dashboard and Insights won't count credit card balances. " +
                        "Independent of the toggle above — credit cards can stay visible while excluded, or vice versa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.excludeCreditCardFromTotal,
                onCheckedChange = viewModel::updateExcludeCreditCardFromTotal,
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
    var enabling by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("App lock", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (canAuthenticate) {
                    "Require your fingerprint, face, or device PIN once the app has " +
                        "been closed or idle beyond the auto-lock time below."
                } else {
                    "Unavailable: set up a screen lock or enroll biometrics on this device first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = viewModel.appLockEnabled,
            enabled = canAuthenticate && !enabling && activity != null,
            onCheckedChange = { wantEnabled ->
                if (!wantEnabled) {
                    viewModel.updateAppLockEnabled(false)
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

// ---------------------------------------------------------------------------
// Subpage: Design (Classic vs Premium)
// ---------------------------------------------------------------------------

@Composable
fun DesignSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val designMode by viewModel.designMode.collectAsStateWithLifecycle()
    SettingsSubpage(title = "Design", onBack = onBack) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Premium design", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Material 3 Expressive theming, a GPU-shader AI-parsing indicator, and a " +
                        "springier transaction detail dialog. Falls back to the plain Classic look " +
                        "on anything that can't render an effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = designMode == DesignMode.PREMIUM,
                onCheckedChange = { checked ->
                    viewModel.setDesignMode(if (checked) DesignMode.PREMIUM else DesignMode.CLASSIC)
                },
            )
        }
    }
}

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

/** Auto-lock grace-window choices, in minutes: 1/2/5/15 min, then 1/6/12 hours. */
val APP_LOCK_TIMEOUT_OPTIONS_MIN = listOf(1, 2, 5, 15, 60, 360, 720)

/** "15 min" / "6 hr" — whole-hour multiples render in hours, else minutes. */
fun appLockTimeoutLabel(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} hr" else "$minutes min"

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
