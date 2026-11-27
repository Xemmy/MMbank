package com.xemmy.mmbank.ui.screens.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.ListRow
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.theme.MMTheme

/** Every label with how many transactions use it: add, rename, merge into another, delete. */
@Composable
fun LabelsScreen(
    onBack: () -> Unit,
    viewModel: LabelsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LabelRow?>(null) }
    var deleting by remember { mutableStateOf<LabelRow?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var mergeFrom by remember { mutableStateOf<LabelRow?>(null) }

    MMScaffold(
        title = "Labels",
        onBack = onBack,
        actions = { TextButton(onClick = { adding = true }) { Text("Add") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message?.let { msg ->
                item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No labels yet. Add one here, or while adding a transaction.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MMTheme.colors.textSecondary,
                    )
                }
            }
            items(rows, key = { it.label.id }) { row ->
                MMCard(Modifier.fillMaxWidth(), onClick = { menuFor = row.label.id }) {
                    ListRow(
                        title = row.label.name,
                        subtitle = "${row.uses} transaction${if (row.uses == 1) "" else "s"}",
                        trailing = {
                            androidx.compose.foundation.layout.Box {
                                TextButton(onClick = { menuFor = row.label.id }) { Text("Edit") }
                                DropdownMenu(expanded = menuFor == row.label.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = row })
                                    if (rows.size > 1) {
                                        DropdownMenuItem(text = { Text("Merge into…") }, onClick = { menuFor = null; mergeFrom = row })
                                    }
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; deleting = row })
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (adding) NameDialog("New label", "", { adding = false }) { viewModel.create(it); adding = false }
    renaming?.let { row -> NameDialog("Rename label", row.label.name, { renaming = null }) { viewModel.rename(row.label.id, it); renaming = null } }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${row.label.name}”?") },
            text = { Text("It is removed from ${row.uses} transaction${if (row.uses == 1) "" else "s"}. The transactions stay.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(row.label.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    mergeFrom?.let { from ->
        AlertDialog(
            onDismissRequest = { mergeFrom = null },
            title = { Text("Merge “${from.label.name}” into") },
            text = {
                androidx.compose.foundation.layout.Column {
                    rows.filter { it.label.id != from.label.id }.forEach { target ->
                        TextButton(onClick = { viewModel.merge(from.label.id, target.label.id); mergeFrom = null }) { Text(target.label.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeFrom = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
