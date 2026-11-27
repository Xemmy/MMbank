package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider

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
