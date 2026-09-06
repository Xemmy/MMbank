package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.usecase.ImportPastedMessagesUseCase
import com.xemmy.mmbank.ui.AppViewModelProvider

/**
 * Two halves of the same idea — deciding, by example, what the app does with a
 * message.
 *
 * "Import" takes a pasted backlog of bank messages and records them as if they
 * had been captured live (dates read from the messages themselves, anything
 * already in the ledger skipped). "Ignored" takes a pasted example of a
 * message that should never have been captured and mutes everything shaped
 * like it from then on.
 */
@Composable
fun ImportMessagesSettingsScreen(
    onBack: () -> Unit,
    viewModel: ImportMessagesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    var tab by remember { mutableIntStateOf(0) }

    SettingsSubpage(title = "Import & ignore messages", onBack = onBack) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Import") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Ignored") })
        }
        when (tab) {
            0 -> ImportTab(viewModel)
            else -> IgnoredTab(viewModel)
        }
        viewModel.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun ImportTab(viewModel: ImportMessagesViewModel) {
    Text(
        "Paste bank messages — one per line, or separated by blank lines. Each is parsed with " +
            "your usual rules, dated from the date printed inside it, and skipped if it's already " +
            "in your ledger.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = viewModel.importInput,
        onValueChange = { viewModel.importInput = it },
        label = { Text("Paste messages") },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = viewModel::import,
            enabled = !viewModel.busy && viewModel.importInput.isNotBlank(),
        ) {
            val count = viewModel.pendingCount
            Text(if (count > 0) "Import $count message${if (count == 1) "" else "s"}" else "Import")
        }
        if (viewModel.busy) CircularProgressIndicator(Modifier.size(20.dp))
    }
    viewModel.lastSummary?.let { ImportSummaryCard(it) }
}

@Composable
private fun ImportSummaryCard(summary: ImportPastedMessagesUseCase.Summary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last import", style = MaterialTheme.typography.titleSmall)
            SummaryLine("Imported", summary.imported)
            SummaryLine("Already in your ledger", summary.skippedDuplicates)
            SummaryLine("Skipped by the ignore list", summary.ignored)
            SummaryLine("Not recognised as transactions", summary.unparsed)
            if (summary.unparsed > 0) {
                Text(
                    "The text is still in the box so you don't lose it. A parsing rule for that " +
                        "format would let these through next time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$count", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IgnoredTab(viewModel: ImportMessagesViewModel) {
    val ignored by viewModel.ignored.collectAsStateWithLifecycle()

    Text(
        "Paste a message you never want captured — a promo, an OTP, a marketing blast. Messages " +
            "shaped like it are skipped from then on, whatever numbers they carry.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = viewModel.ignoreInput,
        onValueChange = { viewModel.ignoreInput = it },
        label = { Text("Paste an example message") },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp),
    )
    Button(
        onClick = viewModel::addIgnorePattern,
        enabled = !viewModel.busy && viewModel.ignoreInput.isNotBlank(),
    ) { Text("Ignore messages like this") }

    if (ignored.isEmpty()) {
        Text(
            "Nothing is being ignored yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column {
                ignored.forEachIndexed { index, pattern ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pattern.sample,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.deleteIgnorePattern(pattern.id) },
                            enabled = !viewModel.busy,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Stop ignoring")
                        }
                    }
                }
            }
        }
    }
}
