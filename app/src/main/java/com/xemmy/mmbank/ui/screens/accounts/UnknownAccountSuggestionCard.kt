package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.account.UnknownAccountSuggestions
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.math.BigDecimal

/**
 * "5 messages from card ••4543 -- add it?" for an ending that matches none of
 * the user's accounts. Two ways out, both of which file the messages away.
 */
@Composable
internal fun UnknownAccountSuggestionCard(
    suggestion: UnknownAccountSuggestions.Suggestion,
    hidden: Boolean,
    onAddNew: () -> Unit,
    onLinkExisting: () -> Unit,
) {
    MMCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (suggestion.count == 1) "1 message" else "${suggestion.count} messages") + " from ${suggestion.label}",
                style = MaterialTheme.typography.bodyLarge,
                color = MMTheme.colors.textPrimary,
            )
            val sender = suggestion.senders.firstOrNull()
            Text(
                "Not matched to any of your accounts" + (sender?.let { " · $it" } ?: "") + " · ${suggestion.currencyCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
            suggestion.latestReportedBalance?.let { printed ->
                val what = if (suggestion.latestReportedKind == ReportedBalanceKind.AVAILABLE_CREDIT) "Available credit" else "Balance"
                Text(
                    "$what the bank last printed: " + moneyLabel(printed, suggestion.currencyCode, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddNew) { Text("Add it") }
                OutlinedButton(onClick = onLinkExisting) { Text("It's an account I have") }
            }
        }
    }
}

/** Names the new account; the opening balance is worked out so it starts matching the bank. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AddFromSuggestionDialog(
    suggestion: UnknownAccountSuggestions.Suggestion,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: AccountType, creditLimit: BigDecimal?) -> Unit,
) {
    var type by remember { mutableStateOf(suggestion.suggestedType) }
    var name by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    val creditLimit = parseMoneyInput(limit)?.takeIf { it.signum() > 0 }
    val isCard = type == AccountType.CREDIT_CARD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${suggestion.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CREDIT_CARD).forEach { entry ->
                        MMFilterChip(selected = type == entry, onClick = { type = entry }, label = typeLabel(entry))
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCard) {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it },
                        label = { Text("Credit limit (needed to start matching the bank)") },
                        singleLine = true,
                        isError = limit.isNotBlank() && creditLimit == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "The ${suggestion.count} message" + (if (suggestion.count == 1) "" else "s") +
                        " will be filed here, and the opening balance set so it matches what the bank printed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (!isCard || limit.isBlank() || creditLimit != null),
                onClick = { onConfirm(name.trim(), type, if (isCard) creditLimit else null) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks which existing account the ending belongs to; only accounts in the same currency are offered. */
@Composable
internal fun LinkToExistingDialog(
    suggestion: UnknownAccountSuggestions.Suggestion,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onPick: (Account) -> Unit,
) {
    val candidates = accounts.filter { it.currencyCode.equals(suggestion.currencyCode, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which account is ${suggestion.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.isEmpty()) {
                    Text(
                        "You have no ${suggestion.currencyCode} account to add it to. Add it as a new account instead.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "The ending ${suggestion.digits} is remembered on it, so future messages file themselves. " +
                            "Messages from before its last balance update are filed without changing its balance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MMTheme.colors.textSecondary,
                    )
                    candidates.forEach { account ->
                        OutlinedButton(onClick = { onPick(account) }, modifier = Modifier.fillMaxWidth()) {
                            Text(account.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun typeLabel(type: AccountType): String = when (type) {
    AccountType.CHECKING -> "Current"
    AccountType.SAVINGS -> "Savings"
    AccountType.CREDIT_CARD -> "Credit card"
    else -> type.name.lowercase().replaceFirstChar { it.uppercase() }
}
