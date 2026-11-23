package com.xemmy.mmbank.ui.screens.settings

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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.ai.AiPrivacyStatus
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.theme.displayName

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
                            SettingsEntry.LABELS -> MenuRowSpec(
                                Icons.Filled.PieChart, "Labels",
                                "Tags you put on transactions — rename, merge, delete",
                            )
                            SettingsEntry.MERCHANT_RENAMES -> MenuRowSpec(
                                Icons.Filled.Tune, "Merchant renames",
                                "Names you chose for future messages — review or delete",
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
