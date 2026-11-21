package com.xemmy.mmbank.ui.screens.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Rename all [count] transactions named [from] to [ new name ]" with an
 * "Also rename future messages" checkbox. [onConfirm] gets the trimmed new name
 * and the checkbox; the caller runs MerchantAliasRepository.renameMerchant.
 */
@Composable
fun RenameMerchantDialog(
    from: String,
    count: Int,
    initialTo: String = "",
    onDismiss: () -> Unit,
    onConfirm: (to: String, alsoFuture: Boolean) -> Unit,
) {
    var to by remember { mutableStateOf(initialTo) }
    var alsoFuture by remember { mutableStateOf(true) }
    val valid = to.isNotBlank() && !to.trim().equals(from.trim(), ignoreCase = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename merchant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Rename all $count transaction${if (count == 1) "" else "s"} named \"$from\" to:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = to, onValueChange = { to = it },
                    label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoFuture, onCheckedChange = { alsoFuture = it })
                    Text("Also rename future messages", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(to.trim(), alsoFuture) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
