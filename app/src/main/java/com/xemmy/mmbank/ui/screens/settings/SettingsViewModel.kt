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
import com.xemmy.mmbank.domain.ai.RetentionBackupGate
import com.xemmy.mmbank.domain.ai.RetentionPreview
import com.xemmy.mmbank.domain.ai.TransactionRetentionPlanner
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
import com.xemmy.mmbank.domain.model.ThemeMode
import com.xemmy.mmbank.domain.people.PersonCounts
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.util.CaptureAccess
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import com.xemmy.mmbank.ui.screens.budgets.CategoriesTabContent
import com.xemmy.mmbank.ui.screens.budgets.CategoriesViewModel
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.theme.displayName
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch


/** Obviously-fake sample used by the "Send test message" button -- never persisted, only logged. */
private const val TEST_AI_MESSAGE = "TEST MESSAGE — You spent AED 42.00 at Test Merchant. Your available balance is now AED 958.00."

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var defaultCurrency by mutableStateOf(container.preferences.defaultCurrency)
    // Activity tab Premium hero card's budget consumption bar. <= 0 (or blank)
    // means no budget is set; the input mirrors aiDailyRequestLimitInput's
    // "type freely, Save commits" shape rather than saving on every keystroke.
    var monthlySpendBudgetInput by mutableStateOf(
        container.preferences.monthlySpendBudget.takeIf { it > 0.0 }?.toString().orEmpty()
    )
    var subscriptionRemindersEnabled by mutableStateOf(container.preferences.subscriptionRemindersEnabled)
        private set
    var billRemindersEnabled by mutableStateOf(container.preferences.billRemindersEnabled)
        private set

    var unusualChargeAlertsEnabled by mutableStateOf(container.preferences.unusualChargeAlertsEnabled)
        private set
    var weeklyDigestEnabled by mutableStateOf(container.preferences.weeklyDigestNotificationsEnabled)
        private set
    var hasAppPin by mutableStateOf(container.hasAppPin)
        private set
    var secureScreen by mutableStateOf(container.secureScreen.value)
        private set
    var hideZeroBalances by mutableStateOf(container.preferences.hideZeroBalances)
    var budgetRollover by mutableStateOf(container.preferences.budgetRollover)
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
    /** What the selected retention window would delete today; null until computed or when retention is off. */
    var retentionPreview by mutableStateOf<String?>(null)
        private set

    var transactionRetentionMonths by mutableStateOf(container.preferences.transactionRetentionMonths)
        private set

    val themeMode: StateFlow<ThemeMode> = container.themeMode
    fun setThemeMode(mode: ThemeMode) = container.setThemeMode(mode)

    // Ambient background style: a reactive flow (not a mutableStateOf snapshot)
    // since the root app scaffold applies it live the instant it's changed here.
    val backgroundStyle: StateFlow<BackgroundStyle> = container.backgroundPreset
    fun setBackgroundStyle(style: BackgroundStyle) = container.setBackgroundPreset(style)

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

    /**
     * Transaction count per person (keys normalized by [PersonCounts]) -- drives
     * the reassign-on-delete prompt. Compose state, not a plain field, so a
     * dialog reading it re-renders when the ledger emits.
     */
    private var personCounts by mutableStateOf<Map<String, Int>>(emptyMap())

    /**
     * False until the ledger's first emission. Until then a count of zero means
     * "not loaded yet", not "nobody uses this name" -- see [personDeleteNeedsPrompt].
     */
    private var personCountsLoaded by mutableStateOf(false)

    init {
        viewModelScope.launch {
            container.transactionRepository.observeTransactions()
                .catch { emit(emptyList()) }
                .collect { txs ->
                    val names = txs.map { it.person }
                    transactionPersons = names.mapNotNull { it?.trim() }
                        .filter { it.isNotEmpty() }
                        .distinctBy { PersonCounts.key(it) }
                    personCounts = PersonCounts.countByName(names)
                    personCountsLoaded = true
                    recomputeUnlisted()
                }
        }
    }

    fun transactionCountForPerson(name: String): Int = PersonCounts.countFor(personCounts, name)

    /**
     * Whether removing [name] must go through the reassign picker rather than
     * deleting outright. Errs toward prompting while counts are still loading:
     * a stale zero would otherwise strip the person off transactions silently,
     * which is exactly what the picker exists to prevent.
     */
    fun personDeleteNeedsPrompt(name: String): Boolean =
        !personCountsLoaded || transactionCountForPerson(name) > 0

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

    /**
     * Removes [name] from the saved list and, if [reassignTo] is given,
     * moves every transaction carrying [name] onto it; otherwise those
     * transactions become unassigned. Also used for the plain "clear from
     * transactions" case by passing a null [reassignTo].
     */
    fun deletePersonAndReassign(name: String, reassignTo: String?) {
        if (busy) return
        val target = reassignTo?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            busy = true
            runCatching {
                container.transactionRepository.renamePerson(name, target)
                container.preferences.persons = container.preferences.persons - name
                if (container.preferences.defaultPerson == name) {
                    container.preferences.defaultPerson = target
                    defaultPerson = target.orEmpty()
                }
                persons = container.preferences.persons
                recomputeUnlisted()
            }.onSuccess {
                statusMessage = if (target != null) {
                    "Moved $name's transactions to $target"
                } else {
                    "Removed $name from all transactions"
                }
            }.onFailure {
                container.recordError("SettingsViewModel.deletePerson", it)
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

    /** Blank input clears the budget (the hero card then shows the total with no consumption bar). */
    fun saveMonthlySpendBudget() {
        val trimmed = monthlySpendBudgetInput.trim()
        if (trimmed.isEmpty()) {
            container.preferences.monthlySpendBudget = 0.0
            statusMessage = "Monthly budget cleared"
            return
        }
        val amount = trimmed.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            monthlySpendBudgetInput =
                container.preferences.monthlySpendBudget.takeIf { it > 0.0 }?.toString().orEmpty()
            statusMessage = "Budget must be a positive amount"
            return
        }
        container.preferences.monthlySpendBudget = amount
        statusMessage = "Monthly budget set to ${moneyLabel(amount, defaultCurrency)}"
    }

    /**
     * Schedules or cancels one of the app's periodic work items. Off by
     * default -- see [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker]
     * for why this is opt-in rather than always on.
     */
    fun updateSubscriptionReminders(enabled: Boolean) {
        container.setSubscriptionRemindersEnabled(enabled)
        subscriptionRemindersEnabled = enabled
        statusMessage = if (enabled) {
            "Renewal reminders on — checked once a day."
        } else {
            "Renewal reminders off."
        }
    }

    /**
     * Schedules or cancels one of the app's periodic work items, independent
     * of [updateSubscriptionReminders] -- see
     * [com.xemmy.mmbank.data.bills.BillReminderWorker].
     */
    fun updateBillReminders(enabled: Boolean) {
        container.setBillRemindersEnabled(enabled)
        billRemindersEnabled = enabled
        statusMessage = if (enabled) {
            "Bill reminders on — checked once a day."
        } else {
            "Bill reminders off."
        }
    }

    /**
     * Opt-in phone notification for unusual charges (see
     * [com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector]). Checked
     * on the capture path, so nothing is scheduled; the in-app insight shows
     * either way.
     */
    fun updateUnusualChargeAlerts(enabled: Boolean) {
        container.preferences.unusualChargeAlertsEnabled = enabled
        unusualChargeAlertsEnabled = enabled
        statusMessage = if (enabled) "Unusual charge alerts on." else "Unusual charge alerts off."
    }

    /** Sets (or changes) the app PIN; hashing runs off the main thread. */
    fun setAppPin(pin: String) {
        viewModelScope.launch {
            container.setAppPin(pin)
            hasAppPin = true
            statusMessage = "App PIN set."
        }
    }

    /** Removes the app PIN; turns the lock off too if nothing else could open it. */
    fun removeAppPin() {
        container.removeAppPin()
        hasAppPin = false
        appLockEnabled = container.preferences.appLockEnabled
        statusMessage = if (appLockEnabled) "App PIN removed." else "App PIN removed. App lock is off: this phone has no other way to unlock it."
    }

    fun updateSecureScreen(enabled: Boolean) {
        container.setSecureScreen(enabled)
        secureScreen = enabled
        statusMessage = if (enabled) "Screenshots blocked." else "Screenshots allowed."
    }

    /** Schedules or cancels the weekly "what changed" notification (see [com.xemmy.mmbank.data.digest.WeeklyDigestWorker]). */
    fun updateWeeklyDigest(enabled: Boolean) {
        container.setWeeklyDigestEnabled(enabled)
        weeklyDigestEnabled = enabled
        statusMessage = if (enabled) "Weekly digest on: once a week, what changed." else "Weekly digest off."
    }

    fun updateBudgetRollover(on: Boolean) {
        container.preferences.budgetRollover = on
        budgetRollover = on
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

    /** Recomputes [retentionPreview] with the same planner the weekly prune runs. */
    fun refreshRetentionPreview() {
        val months = transactionRetentionMonths
        if (months <= 0) {
            retentionPreview = null
            return
        }
        viewModelScope.launch {
            retentionPreview = runCatching {
                val rows = container.transactionRepository.getAllReal()
                val plan = TransactionRetentionPlanner.plan(rows, months, System.currentTimeMillis())
                val now = System.currentTimeMillis()
                val last = container.preferences.lastBackupAt
                RetentionPreview.describe(
                    RetentionPreview.summarize(plan, rows),
                    RetentionPreview.BackupContext(RetentionBackupGate.status(last, now), last, now),
                )
            }.getOrNull()
        }
    }

    /** `0` means "keep everything forever" -- turns the retention prune off and cancels its schedule. */
    fun updateTransactionRetentionMonths(months: Int) {
        container.setTransactionRetentionMonths(months)
        transactionRetentionMonths = months
        refreshRetentionPreview()
        statusMessage = if (months <= 0) {
            "Transaction history kept forever."
        } else {
            "Old, unremarkable transactions older than ${transactionRetentionLabel(months)} will be pruned weekly."
        }
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
                    val parsed = AiResponseParser.parse(raw, container.preferences.defaultCurrency)
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
    var backupPasswordConfirm by mutableStateOf("")

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
                // A restorable backup now exists, which is what lets the retention prune run.
                container.preferences.lastBackupAt = System.currentTimeMillis()
                refreshRetentionPreview()
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
