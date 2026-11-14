package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.ui.screens.dashboard.AccountRow
import com.xemmy.mmbank.ui.util.asMoneyOrMask

/** "CREDIT_CARD" -> "Credit card". */
private fun Account.typeLabel(): String =
    type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/**
 * Account settings modal (an [AlertDialog], not a `ModalBottomSheet` -- see
 * [CategoryPickerBottomSheet]'s doc comment for why every overlay in this app
 * is a real dialog window). Lists every real account with its balance, an
 * editable nickname (renames the account, tap to edit), its type, and a
 * "show on Dashboard" switch that hides/reveals its card in [AccountCarousel]
 * without touching net worth or archiving it.
 */
@Composable
fun ManageAccountsBottomSheet(
    accounts: List<AccountRow>,
    hidden: Boolean,
    onRename: (Account, String) -> Unit,
    onToggleVisibility: (Account) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage accounts") },
        text = {
            if (accounts.isEmpty()) {
                Text(
                    "No accounts yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(accounts, key = { it.account.id }) { row ->
                        ManageAccountRow(
                            row = row,
                            hidden = hidden,
                            onRename = { newName -> onRename(row.account, newName) },
                            onToggleVisibility = { onToggleVisibility(row.account) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ManageAccountRow(
    row: AccountRow,
    hidden: Boolean,
    onRename: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    var editing by remember(row.account.id) { mutableStateOf(false) }
    var draftName by remember(row.account.id) { mutableStateOf(row.account.name) }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        label = { Text("Nickname") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        row.account.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { editing = true },
                    )
                }
                Text(
                    row.account.typeLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                row.balance.asMoneyOrMask(row.account.currencyCode, hidden),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = !row.account.hiddenFromDashboard,
                onCheckedChange = { onToggleVisibility() },
            )
        }
        if (editing) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { draftName = row.account.name; editing = false }) { Text("Cancel") }
                TextButton(
                    onClick = {
                        val trimmed = draftName.trim()
                        if (trimmed.isNotEmpty()) onRename(trimmed)
                        editing = false
                    },
                    enabled = draftName.isNotBlank(),
                ) { Text("Save") }
            }
        }
    }
}
