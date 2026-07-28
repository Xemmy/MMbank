package com.xemmy.mmbank.ui.screens.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GoalsUiState(
    val goals: List<SavingsGoal> = emptyList(),
    val currency: String = "USD",
    /** This month's net surplus (income - expenses) so far, floored at zero -- feeds "Auto-Allocate Surplus". */
    val monthSurplus: Double = 0.0,
)

class GoalsViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<GoalsUiState> = combine(
        container.savingsGoalRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
    ) { goals, transactions, rates ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monthStartMillis = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStartMillis = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        fun inBase(tx: Transaction): Double? =
            CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase)

        val monthTx = transactions.filter {
            !it.excludeFromStats && it.timestampMillis >= monthStartMillis && it.timestampMillis < nextMonthStartMillis
        }
        val income = monthTx.filter { it.direction == TransactionDirection.CREDIT }.sumOf { inBase(it) ?: 0.0 }
        val expenses = monthTx.filter { it.direction == TransactionDirection.DEBIT }.sumOf { inBase(it) ?: 0.0 }

        GoalsUiState(goals = goals, currency = base, monthSurplus = (income - expenses).coerceAtLeast(0.0))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    fun addGoal(title: String, targetAmount: BigDecimal, icon: String, targetDateMillis: Long?) {
        val clean = title.trim()
        if (clean.isEmpty() || targetAmount.signum() <= 0) return
        viewModelScope.launch {
            runCatching {
                container.savingsGoalRepository.save(
                    SavingsGoal(
                        title = clean,
                        targetAmount = targetAmount,
                        icon = icon.ifBlank { "🎯" },
                        targetDateMillis = targetDateMillis,
                    )
                )
            }.onFailure { container.recordError("GoalsViewModel.addGoal", it) }
        }
    }

    /** Adds [amount] to [goalId]'s progress -- "Auto-Allocate Surplus". */
    fun allocateSurplus(goalId: Long, amount: BigDecimal) {
        if (amount.signum() <= 0) return
        viewModelScope.launch {
            runCatching { container.savingsGoalRepository.allocate(goalId, amount) }
                .onFailure { container.recordError("GoalsViewModel.allocateSurplus", it) }
        }
    }

    fun archive(goalId: Long) {
        viewModelScope.launch {
            runCatching { container.savingsGoalRepository.archive(goalId) }
                .onFailure { container.recordError("GoalsViewModel.archive", it) }
        }
    }
}

/**
 * Savings Goals & Debt Payoff Tracker: named targets (Emergency Fund,
 * Vacation, Debt Payoff, …) with a progress bar each, plus "Auto-Allocate
 * Surplus" to sweep this month's net surplus into a chosen goal.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    viewModel: GoalsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var allocateOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Savings goals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add goal")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.monthSurplus > 0.0 && state.goals.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Savings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Surplus this month", style = MaterialTheme.typography.titleSmall)
                            Text(
                                state.monthSurplus.asMoney(state.currency) + " available to allocate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { allocateOpen = true }) { Text("Allocate") }
                    }
                }
            }

            if (state.goals.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No savings goals yet. Tap + to add an Emergency Fund, Vacation, " +
                            "Debt Payoff or any other target.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.goals, key = { it.id }) { goal ->
                        GoalRow(goal, state.currency, onArchive = { viewModel.archive(goal.id) })
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddGoalDialog(
            currency = state.currency,
            onDismiss = { addOpen = false },
            onAdd = { title, target, icon ->
                viewModel.addGoal(title, target, icon, null)
                addOpen = false
            },
        )
    }

    if (allocateOpen) {
        AllocateSurplusDialog(
            goals = state.goals,
            surplus = state.monthSurplus,
            currency = state.currency,
            onDismiss = { allocateOpen = false },
            onAllocate = { goalId, amount ->
                viewModel.allocateSurplus(goalId, amount)
                allocateOpen = false
            },
        )
    }
}

@Composable
private fun GoalRow(goal: SavingsGoal, currency: String, onArchive: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(goal.icon, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(goal.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    goal.targetDateMillis?.let { millis ->
                        Text(
                            "By " + Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                                .format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onArchive) {
                    Icon(Icons.Filled.Close, contentDescription = "Archive ${goal.title}")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { goal.progressFraction.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    goal.currentAmount.asMoney(currency) + " of " + goal.targetAmount.asMoney(currency),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(goal.progressFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AddGoalDialog(
    currency: String,
    onDismiss: () -> Unit,
    onAdd: (title: String, target: BigDecimal, icon: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🎯") }
    val targetAmount = target.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New savings goal") },
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
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target amount ($currency)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && targetAmount != null && targetAmount > 0.0,
                onClick = { onAdd(title, BigDecimal.valueOf(targetAmount ?: 0.0), icon) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AllocateSurplusDialog(
    goals: List<SavingsGoal>,
    surplus: Double,
    currency: String,
    onDismiss: () -> Unit,
    onAllocate: (goalId: Long, amount: BigDecimal) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(goals.firstOrNull()) }
    var amount by remember { mutableStateOf(surplus.toString()) }
    val amountValue = amount.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-allocate surplus") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This month's remaining surplus: ${surplus.asMoney(currency)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box {
                    Card(
                        Modifier.fillMaxWidth().clickable { expanded = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selected?.let { "${it.icon} ${it.title}" } ?: "Select a goal",
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        goals.forEach { goal ->
                            DropdownMenuItem(
                                text = { Text("${goal.icon} ${goal.title}") },
                                onClick = { selected = goal; expanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ($currency)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && amountValue != null && amountValue > 0.0,
                onClick = { selected?.let { onAllocate(it.id, BigDecimal.valueOf(amountValue ?: 0.0)) } },
            ) { Text("Allocate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
