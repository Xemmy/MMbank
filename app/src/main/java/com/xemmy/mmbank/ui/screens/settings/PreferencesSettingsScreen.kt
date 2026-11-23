package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider

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
