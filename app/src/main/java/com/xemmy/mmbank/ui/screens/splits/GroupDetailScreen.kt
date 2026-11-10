package com.xemmy.mmbank.ui.screens.splits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.model.SplitType
import com.xemmy.mmbank.domain.split.DebtSimplifier
import com.xemmy.mmbank.domain.split.GroupBalances
import com.xemmy.mmbank.domain.split.SplitAllocator
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupDetailUiState(
    val group: SplitGroup? = null,
    val memberFriends: List<Friend> = emptyList(),
    val expenses: List<GroupExpense> = emptyList(),
    /** An expense's current splits, for pre-filling the edit dialog. */
    val splitsByExpenseId: Map<Long, List<ExpenseSplit>> = emptyMap(),
    /** Net balance per participant within this group only (null = "me"). */
    val balances: Map<Long?, BigDecimal> = emptyMap(),
    val currency: String = "USD",
    val settlements: List<DebtSimplifier.Settlement<Long?>> = emptyList(),
)

class GroupDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    private val groupId: Long = savedStateHandle.get<Long>("groupId") ?: -1L

    val uiState: StateFlow<GroupDetailUiState> = combine(
        container.splitGroupRepository.observeActive(),
        container.friendRepository.observeActive(),
        container.splitGroupRepository.observeAllExpenses(),
        container.splitGroupRepository.observeAllSplits(),
    ) { groups, friends, allExpenses, allSplits ->
        val group = groups.find { it.id == groupId }
        val memberFriends = friends.filter { it.id in group?.memberFriendIds.orEmpty() }
        val expenses = allExpenses.filter { it.groupId == groupId }.sortedByDescending { it.createdAt }
        val splitsByExpenseId = allSplits.groupBy { it.expenseId }
        val balances = GroupBalances.compute(expenses, splitsByExpenseId)
        GroupDetailUiState(
            group = group,
            memberFriends = memberFriends,
            expenses = expenses,
            splitsByExpenseId = splitsByExpenseId,
            balances = balances,
            currency = container.preferences.defaultCurrency,
            settlements = DebtSimplifier.simplify(balances),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailUiState())

    /** Inserts a new expense ([expenseId] = 0) or updates an existing one, replacing its splits wholesale. */
    fun saveExpense(
        expenseId: Long,
        description: String,
        amount: BigDecimal,
        paidByFriendId: Long?,
        splitType: SplitType,
        splits: Map<Long?, BigDecimal>,
    ) {
        val clean = description.trim()
        if (clean.isEmpty() || amount.signum() <= 0 || splits.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val expense = GroupExpense(
                    id = expenseId,
                    groupId = groupId,
                    description = clean,
                    amount = amount,
                    currencyCode = container.preferences.defaultCurrency,
                    paidByFriendId = paidByFriendId,
                    splitType = splitType,
                )
                val splitRows = splits.map { (id, owed) -> ExpenseSplit(expenseId = expenseId, friendId = id, owedAmount = owed) }
                container.splitGroupRepository.saveExpense(expense, splitRows)
            }.onFailure { container.recordError("GroupDetailViewModel.saveExpense", it) }
        }
    }

    fun deleteExpense(expenseId: Long) {
        viewModelScope.launch {
            runCatching { container.splitGroupRepository.deleteExpense(expenseId) }
                .onFailure { container.recordError("GroupDetailViewModel.deleteExpense", it) }
        }
    }

    /** Records one of [GroupDetailUiState.settlements] as an actual paid-and-recorded expense. */
    fun recordSettlement(settlement: DebtSimplifier.Settlement<Long?>) {
        viewModelScope.launch {
            runCatching {
                val expense = GroupExpense(
                    groupId = groupId,
                    description = "Settlement",
                    amount = settlement.amount,
                    currencyCode = container.preferences.defaultCurrency,
                    paidByFriendId = settlement.from,
                    splitType = SplitType.SETTLEMENT,
                )
                val split = ExpenseSplit(expenseId = 0, friendId = settlement.to, owedAmount = settlement.amount)
                container.splitGroupRepository.saveExpense(expense, listOf(split))
            }.onFailure { container.recordError("GroupDetailViewModel.recordSettlement", it) }
        }
    }
}

/** A [SplitGroup]'s member balances, expenses, settle-up suggestions, and the add-expense flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addExpenseOpen by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<GroupExpense?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.let { "${it.icon} ${it.name}" } ?: "Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addExpenseOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add expense")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "balances") {
                BalancesCard(state.balances, state.memberFriends, state.currency)
            }
            if (state.settlements.isNotEmpty()) {
                item(key = "settle_up_header") {
                    Text("Settle up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(state.settlements, key = { "settle_${it.from}_${it.to}" }) { settlement ->
                    SettlementRow(
                        settlement = settlement,
                        friends = state.memberFriends,
                        currency = state.currency,
                        onRecord = { viewModel.recordSettlement(settlement) },
                    )
                }
            }
            item(key = "expenses_header") {
                Text("Expenses", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (state.expenses.isEmpty()) {
                item {
                    Text(
                        "No expenses yet. Tap + to add the first one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.expenses, key = { it.id }) { expense ->
                ExpenseRow(
                    expense = expense,
                    friends = state.memberFriends,
                    currency = state.currency,
                    onEdit = { editingExpense = expense },
                    onDelete = { viewModel.deleteExpense(expense.id) },
                )
            }
        }
    }

    if (addExpenseOpen) {
        ExpenseDialog(
            memberFriends = state.memberFriends,
            currency = state.currency,
            existing = null,
            existingSplits = emptyList(),
            onDismiss = { addExpenseOpen = false },
            onSave = { id, description, amount, paidBy, splitType, splits ->
                viewModel.saveExpense(id, description, amount, paidBy, splitType, splits)
                addExpenseOpen = false
            },
        )
    }

    editingExpense?.let { expense ->
        ExpenseDialog(
            memberFriends = state.memberFriends,
            currency = state.currency,
            existing = expense,
            existingSplits = state.splitsByExpenseId[expense.id].orEmpty(),
            onDismiss = { editingExpense = null },
            onSave = { id, description, amount, paidBy, splitType, splits ->
                viewModel.saveExpense(id, description, amount, paidBy, splitType, splits)
                editingExpense = null
            },
        )
    }
}

private fun friendName(id: Long?, friends: List<Friend>): String =
    if (id == null) "Me" else friends.find { it.id == id }?.name ?: "Unknown"

@Composable
private fun BalancesCard(balances: Map<Long?, BigDecimal>, friends: List<Friend>, currency: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Balances", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val participants = (listOf<Long?>(null) + friends.map { it.id }).distinct()
            participants.forEach { id ->
                val balance = balances[id] ?: BigDecimal.ZERO
                val (text, color) = when {
                    balance.signum() > 0 -> "is owed ${balance.asMoney(currency)}" to MaterialTheme.colorScheme.primary
                    balance.signum() < 0 -> "owes ${balance.negate().asMoney(currency)}" to MaterialTheme.colorScheme.error
                    else -> "settled up" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(friendName(id, friends), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

@Composable
private fun SettlementRow(
    settlement: DebtSimplifier.Settlement<Long?>,
    friends: List<Friend>,
    currency: String,
    onRecord: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${friendName(settlement.from, friends)} pays ${friendName(settlement.to, friends)} " +
                    settlement.amount.asMoney(currency),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRecord) { Text("Record") }
        }
    }
}

@Composable
private fun ExpenseRow(
    expense: GroupExpense,
    friends: List<Friend>,
    currency: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(expense.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${expense.amount.asMoney(currency)} · paid by ${friendName(expense.paidByFriendId, friends)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A settlement's one split is implicit in how it was recorded (see
            // recordSettlement) rather than something the split-type picker
            // below can reconstruct, so it's delete-only, like before.
            if (expense.splitType != SplitType.SETTLEMENT) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${expense.description}")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${expense.description}")
            }
        }
    }
}

@Composable
private fun ExpenseDialog(
    memberFriends: List<Friend>,
    currency: String,
    /** Null for a brand-new expense; the expense being edited otherwise. */
    existing: GroupExpense?,
    /** [existing]'s current splits, for pre-filling the form -- ignored when [existing] is null. */
    existingSplits: List<ExpenseSplit>,
    onDismiss: () -> Unit,
    onSave: (
        id: Long,
        description: String,
        amount: BigDecimal,
        paidByFriendId: Long?,
        splitType: SplitType,
        splits: Map<Long?, BigDecimal>,
    ) -> Unit,
) {
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var amountText by remember { mutableStateOf(existing?.amount?.toPlainString() ?: "") }
    var paidBy by remember { mutableStateOf(existing?.paidByFriendId) }
    var paidByMenuOpen by remember { mutableStateOf(false) }
    // A settlement isn't offered as a pick below (see the dropdown's fixed
    // list), so editing one starts from EQUAL like a brand-new expense would.
    var splitType by remember {
        mutableStateOf(existing?.splitType?.takeIf { it != SplitType.SETTLEMENT } ?: SplitType.EQUAL)
    }
    var splitTypeMenuOpen by remember { mutableStateOf(false) }

    // Union in any participant from the existing splits even if they're no
    // longer a group member, so editing an old expense never silently drops
    // a former member's share.
    val participants = remember(memberFriends, existingSplits) {
        (listOf<Long?>(null) + memberFriends.map { it.id } + existingSplits.map { it.friendId }).distinct()
    }
    val existingSplitByParticipant = remember(existingSplits) { existingSplits.associateBy { it.friendId } }

    // Which participants are included in an EQUAL split; per-participant text
    // for EXACT (amount)/PERCENTAGE (percent)/ADJUSTMENT (+/- amount) --
    // entering nothing (or 0) for a participant just leaves them out.
    var includedInEqual by remember(participants) {
        mutableStateOf(if (existing != null) existingSplitByParticipant.keys else participants.toSet())
    }
    // Only pre-fills from the split type the expense actually used -- picking
    // a different type while editing starts that type's fields blank, same
    // as a brand-new expense, rather than guessing at a cross-type mapping.
    var fieldValues by remember(participants, splitType) {
        mutableStateOf(
            if (existing != null && splitType == existing.splitType) {
                val derived = when (splitType) {
                    SplitType.PERCENTAGE -> SplitAllocator.derivePercentages(
                        existing.amount,
                        existingSplitByParticipant.mapValues { it.value.owedAmount },
                    ).mapValues { it.value.toString() }
                    SplitType.ADJUSTMENT -> SplitAllocator.deriveAdjustments(
                        existing.amount,
                        participants,
                        existingSplitByParticipant.mapValues { it.value.owedAmount },
                    ).mapValues { it.value.toPlainString() }
                    else -> existingSplitByParticipant.mapValues { it.value.owedAmount.toPlainString() }
                }
                participants.associateWith { derived[it] ?: "" }
            } else {
                participants.associateWith { "" }
            }
        )
    }

    val amount = com.xemmy.mmbank.util.AmountInput.parse(amountText)?.takeIf { it.signum() > 0 }

    val splits: Map<Long?, BigDecimal>? = amount?.let { total ->
        when (splitType) {
            SplitType.EQUAL -> {
                val chosen = participants.filter { it in includedInEqual }
                if (chosen.isEmpty()) null else SplitAllocator.splitEqually(total, chosen)
            }
            SplitType.EXACT -> {
                val amounts = fieldValues.mapNotNull { (id, text) ->
                    text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { id to it }
                }.toMap()
                SplitAllocator.splitByExactAmounts(total, amounts)
            }
            SplitType.PERCENTAGE -> {
                val percentages = fieldValues.mapNotNull { (id, text) ->
                    text.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { id to it }
                }.toMap()
                SplitAllocator.splitByPercentages(total, percentages)
            }
            SplitType.ADJUSTMENT -> {
                val adjustments = fieldValues.mapNotNull { (id, text) ->
                    text.toBigDecimalOrNull()?.let { id to it }
                }.toMap()
                SplitAllocator.splitByAdjustments(total, participants, adjustments)
            }
            SplitType.SETTLEMENT -> null // never offered here -- settlements are recorded from the Settle up section
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit expense" else "New expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount ($currency)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PickerRow(
                    label = "Paid by: ${friendName(paidBy, memberFriends)}",
                    expanded = paidByMenuOpen,
                    onOpen = { paidByMenuOpen = true },
                    onDismiss = { paidByMenuOpen = false },
                ) {
                    participants.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(friendName(id, memberFriends)) },
                            onClick = { paidBy = id; paidByMenuOpen = false },
                        )
                    }
                }
                PickerRow(
                    label = "Split: ${splitType.name.lowercase().replaceFirstChar(Char::uppercase)}",
                    expanded = splitTypeMenuOpen,
                    onOpen = { splitTypeMenuOpen = true },
                    onDismiss = { splitTypeMenuOpen = false },
                ) {
                    listOf(SplitType.EQUAL, SplitType.EXACT, SplitType.PERCENTAGE, SplitType.ADJUSTMENT).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            onClick = { splitType = option; splitTypeMenuOpen = false },
                        )
                    }
                }
                when (splitType) {
                    SplitType.EQUAL -> participants.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id in includedInEqual,
                                onCheckedChange = { checked ->
                                    includedInEqual = if (checked) includedInEqual + id else includedInEqual - id
                                },
                            )
                            Text(friendName(id, memberFriends))
                        }
                    }
                    else -> participants.forEach { id ->
                        OutlinedTextField(
                            value = fieldValues[id].orEmpty(),
                            onValueChange = { fieldValues = fieldValues + (id to it) },
                            label = {
                                Text(
                                    friendName(id, memberFriends) + when (splitType) {
                                        SplitType.PERCENTAGE -> " (%)"
                                        SplitType.ADJUSTMENT -> " (+/-)"
                                        else -> " ($currency)"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = description.isNotBlank() && amount != null && splits != null,
                onClick = { onSave(existing?.id ?: 0L, description, amount!!, paidBy, splitType, splits!!) },
            ) { Text(if (existing != null) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(
    label: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    Box {
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = menuContent)
    }
}
