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
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material3.CardDefaults
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
import com.xemmy.mmbank.domain.ai.AiPrivacyStatus
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
    val backgroundStyle by viewModel.backgroundStyle.collectAsStateWithLifecycle()

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

        SettingsGroup.entries.forEach { group ->
            MenuSectionLabel(group.title)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                        val row = when (entry) {
                            SettingsEntry.CAPTURE -> MenuRowSpec(
                                Icons.Filled.NotificationsActive, "Capture & permissions",
                                if (captureOk) "All channels enabled" else "Action needed",
                                if (captureOk) LedgerColors.credit else MaterialTheme.colorScheme.error,
                            )
                            SettingsEntry.IMPORT -> MenuRowSpec(
                                Icons.Filled.ContentPaste, "Import & ignore messages",
                                "Paste a backlog to import — or an example to stop capturing",
                            )
                            SettingsEntry.PREFERENCES -> MenuRowSpec(
                                Icons.Filled.Tune, "Currency & preferences",
                                "Currency ${viewModel.defaultCurrency}" +
                                    (viewModel.defaultPerson.takeIf { it.isNotBlank() }?.let { " · default person $it" } ?: "") +
                                    " · Forex rates",
                            )
                            SettingsEntry.NOTIFICATIONS -> MenuRowSpec(
                                Icons.Filled.Notifications, "Notifications",
                                notificationsSummary(
                                    listOf(
                                        viewModel.subscriptionRemindersEnabled,
                                        viewModel.billRemindersEnabled,
                                        viewModel.unusualChargeAlertsEnabled,
                                        viewModel.weeklyDigestEnabled,
                                    ),
                                ),
                            )
                            SettingsEntry.CATEGORIES_PEOPLE -> MenuRowSpec(
                                Icons.Filled.PieChart, "Categories & People",
                                "Manage categories and saved people — add, edit, delete",
                            )
                            SettingsEntry.BINANCE -> MenuRowSpec(
                                Icons.Filled.CurrencyBitcoin, "Binance",
                                if (viewModel.hasBinanceKeys) "Connected (read-only keys saved)" else "Not configured",
                            )
                            SettingsEntry.RULES -> MenuRowSpec(
                                Icons.Filled.Rule, "Parsing rules", "Teach the app new bank message formats",
                            )
                            SettingsEntry.AI -> MenuRowSpec(
                                Icons.Filled.AutoAwesome, "Local AI parsing",
                                AiPrivacyStatus.menuSubtitle(viewModel.aiParsingEnabled, viewModel.aiProviderMode),
                            )
                            SettingsEntry.APPEARANCE -> MenuRowSpec(
                                Icons.Filled.Palette, "Appearance & Backgrounds", backgroundStyle.displayName,
                            )
                            SettingsEntry.SECURITY -> MenuRowSpec(
                                Icons.Filled.Lock, "App lock & PIN",
                                if (viewModel.appLockEnabled) {
                                    "App lock on · auto-lock ${appLockTimeoutLabel(viewModel.appLockTimeoutMinutes)}"
                                } else {
                                    "Biometric app lock (off)"
                                },
                            )
                            SettingsEntry.BACKUP -> MenuRowSpec(
                                Icons.Filled.Backup, "Backup & restore", "Encrypted export and import",
                            )
                            SettingsEntry.INTEGRITY -> MenuRowSpec(
                                Icons.Filled.VerifiedUser, "Integrity check",
                                "Compare your books with your bank's balances",
                            )
                            SettingsEntry.DIAGNOSTICS -> MenuRowSpec(
                                Icons.Filled.BugReport, "Diagnostics",
                                if (crashRecorded) "Crash log recorded — tap to view" else "No problems recorded",
                                if (crashRecorded) MaterialTheme.colorScheme.error else null,
                            )
                            SettingsEntry.DEMO -> MenuRowSpec(
                                Icons.Filled.Science, "Demo mode",
                                if (viewModel.demoModeEnabled) {
                                    "On — real data hidden behind sample data"
                                } else {
                                    "Show sample data for demos (your data stays safe)"
                                },
                                if (viewModel.demoModeEnabled) MaterialTheme.colorScheme.primary else null,
                            )
                        }
                        MenuRow(
                            icon = row.icon,
                            title = row.title,
                            subtitle = row.subtitle,
                            subtitleColor = row.subtitleColor,
                            onClick = { onOpen(entry.route) },
                        )
                    }
                }
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

/** "2 of 4 on" -- the menu's at-a-glance summary of the notification switches. */
internal fun notificationsSummary(enabled: List<Boolean>): String =
    if (enabled.none { it }) "All off" else "${enabled.count { it }} of ${enabled.size} on"

private class MenuRowSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val subtitleColor: Color? = null,
)

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
fun SettingsSubpage(
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
// Subpage: Categories & People — one organized home for both, tabbed so
// neither crowds the other. Categories reuses CategoriesTabContent (also
// reached via Insights > Categories / the Category budgets card's "Manage"
// button, which route to the same "categories" destination); People is the
// list previously buried inside Preferences, moved here as its own tab.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesAndPeopleScreen(
    onBack: () -> Unit,
    initialTab: Int = 0,
    categoriesViewModel: CategoriesViewModel = viewModel(factory = AppViewModelProvider.Factory),
    settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    var tab by remember { mutableStateOf(initialTab) }
    var creatingNewCategory by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Categories & People") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            // Only Categories has a create action; People adds inline via its own text field.
            if (tab == 0) {
                FloatingActionButton(onClick = { creatingNewCategory = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New category")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Categories") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("People") })
            }
            when (tab) {
                0 -> CategoriesTabContent(
                    creatingNew = creatingNewCategory,
                    onCreatingNewChange = { creatingNewCategory = it },
                    viewModel = categoriesViewModel,
                )
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PeopleSection(settingsViewModel)
                }
            }
        }
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

/**
 * Every phone-notification switch in one place: the four on-device alerts that
 * used to be buried in the middle of Preferences. Each is off until turned on
 * here, and asks for notification permission the first time.
 */
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    SettingsSubpage(title = "Notifications", onBack = onBack) {
        Text(
            "Everything here runs on your phone with no network use, and stays off unless you turn it on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AlertToggleRow(
            title = "Subscription renewal reminders",
            description = "Notifies you a few days before a detected subscription renews. Checks once a " +
                "day on-device with no network use, and stays off unless you want it.",
            checked = viewModel.subscriptionRemindersEnabled,
            onCheckedChange = viewModel::updateSubscriptionReminders,
        )

        AlertToggleRow(
            title = "Bill due-date reminders",
            description = "Notifies you a few days before a manually tracked bill is due. Checks once a " +
                "day on-device with no network use, and stays off unless you want it.",
            checked = viewModel.billRemindersEnabled,
            onCheckedChange = viewModel::updateBillReminders,
        )

        AlertToggleRow(
            title = "Unusual charge alerts",
            description = "Notifies you when a new charge is far above what that merchant usually costs " +
                "you (e.g. 3× your usual taxi fare). Unusual charges always show in Smart insights; " +
                "this only adds a phone notification.",
            checked = viewModel.unusualChargeAlertsEnabled,
            onCheckedChange = viewModel::updateUnusualChargeAlerts,
        )

        AlertToggleRow(
            title = "Weekly digest",
            description = "Once a week, a notification with what you spent and what changed: caps " +
                "crossed, unusual charges, payments due, new subscriptions, and a warning if capture " +
                "has gone quiet. Home's \"This week\" card always shows it.",
            checked = viewModel.weeklyDigestEnabled,
            onCheckedChange = viewModel::updateWeeklyDigest,
        )
    }
}

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
                value = viewModel.monthlySpendBudgetInput,
                onValueChange = { viewModel.monthlySpendBudgetInput = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly spend budget") },
                supportingText = { Text("Drives the Activity tab's Premium hero card. Leave blank for no budget.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = viewModel::saveMonthlySpendBudget) { Text("Save") }
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
                Text("Carry unspent budget forward", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Envelope budgets: what a category's monthly cap didn't use is added to next month's cap " +
                        "(up to 6 months back). Overspending never carries over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.budgetRollover,
                onCheckedChange = viewModel::updateBudgetRollover,
            )
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

/** Retention window choices, in months. `0` means "keep everything forever". */
val TRANSACTION_RETENTION_OPTIONS_MONTHS = listOf(0, 6, 12, 24, 36)

/** "Forever" / "6 mo" / "1 yr" / "3 yr" — whole-year multiples render in years, else months. */
fun transactionRetentionLabel(months: Int): String = when {
    months <= 0 -> "Forever"
    months % 12 == 0 -> "${months / 12} yr"
    else -> "$months mo"
}

/** Editable saved-people list: add, rename (updates every transaction), remove. */
@Composable
private fun PeopleSection(viewModel: SettingsViewModel) {
    var newPerson by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }

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
                        IconButton(onClick = {
                            if (viewModel.personDeleteNeedsPrompt(person)) {
                                confirmingDelete = person
                            } else {
                                viewModel.removePersonFromList(person)
                            }
                        }) {
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
                        IconButton(onClick = { confirmingDelete = person }) {
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

    confirmingDelete?.let { person ->
        val count = viewModel.transactionCountForPerson(person)
        val otherPersons = PersonCounts.reassignTargets(viewModel.persons, viewModel.unlistedPersons, person)
        PersonReassignDialog(
            person = person,
            count = count,
            otherPersons = otherPersons,
            onDismiss = { confirmingDelete = null },
            onConfirm = { reassignTo ->
                viewModel.deletePersonAndReassign(person, reassignTo)
                confirmingDelete = null
            },
        )
    }
}

/**
 * Shown instead of an instant remove when the person being deleted still has
 * transactions on them -- picks where those transactions land (another
 * person, or unassigned) instead of silently orphaning them.
 */
@Composable
private fun PersonReassignDialog(
    person: String,
    count: Int,
    otherPersons: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove “$person”?") },
        text = {
            Column {
                if (count > 0) {
                    Text(
                        "$count transaction${if (count == 1) "" else "s"} " +
                            "${if (count == 1) "is" else "are"} assigned to this person. " +
                            "Choose where to move ${if (count == 1) "it" else "them"}:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        PersonReassignOptionRow(
                            label = "Leave unassigned",
                            selected = selected == null,
                            onClick = { selected = null },
                        )
                    }
                    items(otherPersons) { other ->
                        PersonReassignOptionRow(
                            label = other,
                            selected = selected == other,
                            onClick = { selected = other },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PersonReassignOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
