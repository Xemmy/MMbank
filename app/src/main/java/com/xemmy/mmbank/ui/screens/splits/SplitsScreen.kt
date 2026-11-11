package com.xemmy.mmbank.ui.screens.splits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.split.GroupBalances
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SplitsUiState(
    val groups: List<SplitGroup> = emptyList(),
    val friends: List<Friend> = emptyList(),
    val currency: String = "USD",
    /** groupId -> "me"'s net balance within that group (positive = the group owes me). */
    val myBalanceByGroup: Map<Long, BigDecimal> = emptyMap(),
    /** Sum of "me"'s balance across every group: positive = net owed to me, negative = net I owe. */
    val netOverall: BigDecimal = BigDecimal.ZERO,
)

class SplitsViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<SplitsUiState> = combine(
        container.splitGroupRepository.observeActive(),
        container.friendRepository.observeActive(),
        container.splitGroupRepository.observeAllExpenses(),
        container.splitGroupRepository.observeAllSplits(),
    ) { groups, friends, expenses, splits ->
        val splitsByExpenseId = splits.groupBy { it.expenseId }
        val myBalanceByGroup = groups.associate { group ->
            val groupExpenses = expenses.filter { it.groupId == group.id }
            group.id to (GroupBalances.compute(groupExpenses, splitsByExpenseId)[null] ?: BigDecimal.ZERO)
        }
        SplitsUiState(
            groups = groups,
            friends = friends,
            currency = container.preferences.defaultCurrency,
            myBalanceByGroup = myBalanceByGroup,
            netOverall = myBalanceByGroup.values.fold(BigDecimal.ZERO) { acc, v -> acc + v },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SplitsUiState())

    fun addFriend(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.friendRepository.save(Friend(name = clean)) }
                .onFailure { container.recordError("SplitsViewModel.addFriend", it) }
        }
    }

    fun addGroup(name: String, icon: String, memberFriendIds: List<Long>) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                container.splitGroupRepository.save(
                    SplitGroup(name = clean, icon = icon.trim().ifBlank { "👥" }, memberFriendIds = memberFriendIds)
                )
            }.onFailure { container.recordError("SplitsViewModel.addGroup", it) }
        }
    }

    fun archiveGroup(groupId: Long) {
        viewModelScope.launch {
            runCatching { container.splitGroupRepository.archive(groupId) }
                .onFailure { container.recordError("SplitsViewModel.archiveGroup", it) }
        }
    }
}

/**
 * Splits & Groups: shared-expense groups (Roommates, a trip, …) with a net
 * owed/owing header and a card per group. Expenses, splits and settle-up
 * suggestions live one level down in [com.xemmy.mmbank.ui.screens.splits.GroupDetailScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitsScreen(
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    viewModel: SplitsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addGroupOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Splits & groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addGroupOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add group")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NetBalanceHeader(state.netOverall, state.currency)

            if (state.groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No groups yet. Tap + to start one for a trip, roommates, " +
                            "or anything else you split with someone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.groups, key = { it.id }) { group ->
                        GroupRow(
                            group = group,
                            balance = state.myBalanceByGroup[group.id] ?: BigDecimal.ZERO,
                            currency = state.currency,
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }
            }
        }
    }

    if (addGroupOpen) {
        AddGroupDialog(
            friends = state.friends,
            onDismiss = { addGroupOpen = false },
            onAddFriend = viewModel::addFriend,
            onAdd = { name, icon, memberIds ->
                viewModel.addGroup(name, icon, memberIds)
                addGroupOpen = false
            },
        )
    }
}

@Composable
private fun NetBalanceHeader(netOverall: BigDecimal, currency: String) {
    val (label, color) = when {
        netOverall.signum() > 0 -> "You are owed ${netOverall.asMoney(currency)}" to MaterialTheme.colorScheme.primary
        netOverall.signum() < 0 -> "You owe ${netOverall.negate().asMoney(currency)}" to MaterialTheme.colorScheme.error
        else -> "All settled up" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun GroupRow(group: SplitGroup, balance: BigDecimal, currency: String, onClick: () -> Unit) {
    val (balanceText, balanceColor) = when {
        balance.signum() > 0 -> "You're owed ${balance.asMoney(currency)}" to MaterialTheme.colorScheme.primary
        balance.signum() < 0 -> "You owe ${balance.negate().asMoney(currency)}" to MaterialTheme.colorScheme.error
        else -> "Settled up" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${group.memberFriendIds.size + 1} people",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(balanceText, style = MaterialTheme.typography.bodySmall, color = balanceColor)
        }
    }
}

@Composable
private fun AddGroupDialog(
    friends: List<Friend>,
    onDismiss: () -> Unit,
    onAddFriend: (String) -> Unit,
    onAdd: (name: String, icon: String, memberFriendIds: List<Long>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("👥") }
    var newFriendName by remember { mutableStateOf("") }
    var selectedFriendIds by remember { mutableStateOf(setOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it.take(2) },
                    label = { Text("Icon (emoji)") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                )
                Text("Members", style = MaterialTheme.typography.labelLarge)
                friends.forEach { friend ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = friend.id in selectedFriendIds,
                            onCheckedChange = { checked ->
                                selectedFriendIds = if (checked) {
                                    selectedFriendIds + friend.id
                                } else {
                                    selectedFriendIds - friend.id
                                }
                            },
                        )
                        Text(friend.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFriendName,
                        onValueChange = { newFriendName = it },
                        label = { Text("Add a new friend") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newFriendName.isNotBlank(),
                        onClick = { onAddFriend(newFriendName); newFriendName = "" },
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAdd(name, icon, selectedFriendIds.toList()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
