package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.people.PersonBackfill
import com.xemmy.mmbank.domain.people.PersonCounts
import com.xemmy.mmbank.domain.people.PersonLists
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.screens.budgets.CategoriesTabContent
import com.xemmy.mmbank.ui.screens.budgets.CategoriesViewModel
import com.xemmy.mmbank.ui.designsystem.MMScaffold

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

    MMScaffold(
        title = "Categories & People",
        onBack = onBack,
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

/** Editable saved-people list: add, rename (updates every transaction), remove. */
@Composable
private fun PeopleSection(viewModel: SettingsViewModel) {
    var newPerson by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }
    var fillingMissing by remember { mutableStateOf(false) }

    Text(
        "This list feeds the person menu on transactions and is included in backups. " +
            "Renaming updates every transaction that carries the old name. " +
            "Switch someone off to stop offering them when you assign a person; " +
            "their transactions keep their name and they stay in filters.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (viewModel.persons.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                val ordered = PersonLists.activeFirst(viewModel.persons, viewModel.inactivePersons)
                val firstInactive = ordered.indexOfFirst { !viewModel.isPersonActive(it) }
                ordered.forEachIndexed { index, person ->
                    val active = viewModel.isPersonActive(person)
                    if (index == firstInactive) {
                        Text(
                            "Inactive: not offered when assigning",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (viewModel.isDefaultPerson(person)) "$person · default" else person,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = active,
                            onCheckedChange = { viewModel.setPersonActive(person, it) },
                            modifier = Modifier.semantics {
                                contentDescription = if (active) "$person is active" else "$person is inactive"
                            },
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
                    if (index < ordered.lastIndex) {
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

    if (viewModel.noPersonIds.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                PersonBackfill.summary(viewModel.noPersonIds.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                enabled = viewModel.persons.isNotEmpty(),
                onClick = { fillingMissing = true },
            ) { Text("Assign to…") }
        }
        if (viewModel.persons.isEmpty()) {
            Text(
                "Add a person above first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (fillingMissing) {
        FillMissingPersonDialog(
            count = viewModel.noPersonIds.size,
            choices = PersonLists.assignable(viewModel.persons, viewModel.inactivePersons, viewModel.defaultPerson),
            defaultPerson = viewModel.defaultPerson,
            onDismiss = { fillingMissing = false },
            onConfirm = { name ->
                viewModel.fillMissingPerson(name)
                fillingMissing = false
            },
        )
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

/** One-tap fill: picks who gets every transaction that has no person. Balances are not affected. */
@Composable
private fun FillMissingPersonDialog(
    count: Int,
    choices: List<String>,
    defaultPerson: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(PersonBackfill.preselect(choices, defaultPerson)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign a person") },
        text = {
            Column {
                Text(
                    "${PersonBackfill.summary(count)}. Choose who to give ${if (count == 1) "it" else "them"} to. " +
                        "Balances don't change, and you can still change any one of them later.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(choices) { name ->
                        PersonReassignOptionRow(
                            label = name,
                            selected = selected == name,
                            onClick = { selected = name },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onConfirm) },
            ) { Text("Assign") }
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
