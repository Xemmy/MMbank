package com.xemmy.mmbank.ui.screens.statement

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.ledger.StatementCsvParser
import com.xemmy.mmbank.domain.ledger.StatementReconciler
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.ui.AppViewModelProvider
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatementUiState(
    val accounts: List<Account> = emptyList(),
    val accountId: Long? = null,
    val text: String = "",
    val result: StatementReconciler.Result? = null,
    val skipped: List<String> = emptyList(),
    val parsedLines: Int = 0,
    val message: String? = null,
)

class StatementReconcileViewModel(private val container: AppContainer) : ViewModel() {

    private data class Inputs(val accountId: Long?, val text: String, val outcome: Outcome?)
    private data class Outcome(
        val result: StatementReconciler.Result?,
        val skipped: List<String>,
        val parsed: Int,
        val message: String?,
    )

    private val accountId = MutableStateFlow<Long?>(null)
    private val text = MutableStateFlow("")
    private val outcome = MutableStateFlow<Outcome?>(null)

    val uiState: StateFlow<StatementUiState> = combine(
        container.accountRepository.observeActive(),
        accountId,
        text,
        outcome,
    ) { accounts, selected, pasted, out ->
        StatementUiState(
            accounts = accounts,
            accountId = selected ?: accounts.firstOrNull()?.id,
            text = pasted,
            result = out?.result,
            skipped = out?.skipped.orEmpty(),
            parsedLines = out?.parsed ?: 0,
            message = out?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatementUiState())

    fun selectAccount(id: Long) {
        accountId.value = id
        outcome.value = null
    }

    fun setText(value: String) {
        text.value = value
        outcome.value = null
    }

    fun compare() {
        val selected = uiState.value.accountId ?: run {
            outcome.value = Outcome(null, emptyList(), 0, "Add an account first.")
            return
        }
        viewModelScope.launch {
            runCatching {
                val parsed = StatementCsvParser.parse(text.value)
                if (parsed.lines.isEmpty()) {
                    outcome.value = Outcome(
                        null, parsed.skipped, 0,
                        "No statement lines could be read. Paste the CSV rows, including the date and amount columns.",
                    )
                    return@runCatching
                }
                val rows = container.transactionRepository.observeTransactions().first().filter { it.accountId == selected }
                outcome.value = Outcome(
                    StatementReconciler.reconcile(parsed.lines, rows, zone = ZoneId.systemDefault()),
                    parsed.skipped, parsed.lines.size, null,
                )
            }.onFailure {
                container.recordError("StatementReconcileViewModel.compare", it)
                outcome.value = Outcome(null, emptyList(), 0, "Couldn't compare: ${it.message}")
            }
        }
    }
}

@Composable
fun StatementReconcileScreen(
    onBack: () -> Unit,
    viewModel: StatementReconcileViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currency = state.accounts.firstOrNull { it.id == state.accountId }?.currencyCode.orEmpty()
    val zone = ZoneId.systemDefault()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Compare with a bank statement", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") {
                Text(
                    "Paste the rows of a statement exported as CSV. MMbank pairs each line with a captured transaction " +
                        "(same amount, within 3 days) and shows what is missing on either side. Nothing is changed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "accounts") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = account.id == state.accountId,
                            onClick = { viewModel.selectAccount(account.id) },
                            label = { Text(account.name) },
                        )
                    }
                }
            }
            item(key = "input") {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::setText,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    label = { Text("Statement CSV") },
                    placeholder = { Text("Date,Description,Amount\n2026-11-01,NOON,-120.50") },
                )
            }
            item(key = "go") {
                Button(onClick = viewModel::compare, enabled = state.text.isNotBlank() && state.accountId != null) {
                    Text("Compare")
                }
            }
            state.message?.let { msg ->
                item(key = "message") { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            state.result?.let { result ->
                item(key = "summary") {
                    Text(
                        if (result.isClean) {
                            "Everything matches: all ${result.matched.size} statement lines are in MMbank."
                        } else {
                            "${result.matched.size} matched · ${result.missing.size} on the statement but not in MMbank · " +
                                "${result.extra.size} in MMbank but not on the statement"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (state.skipped.isNotEmpty()) {
                    item(key = "skipped") {
                        Text(
                            "${state.skipped.size} line(s) could not be read and were not compared.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (result.missing.isNotEmpty()) {
                    item(key = "missing_h") { SectionHeader("On the statement, not in MMbank (capture missed these)") }
                    result.missing.forEachIndexed { i, line ->
                        item(key = "missing_$i") {
                            LineRow("${line.date} · ${line.description.ifBlank { "(no description)" }}", "${line.amount.toPlainString()} $currency")
                        }
                    }
                }
                if (result.extra.isNotEmpty()) {
                    item(key = "extra_h") { SectionHeader("In MMbank, not on the statement (duplicate, mis-parse or still pending)") }
                    result.extra.forEachIndexed { i, tx ->
                        val date = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
                        item(key = "extra_$i") {
                            LineRow(
                                "$date · ${tx.merchant?.takeIf { it.isNotBlank() } ?: "(no merchant)"}",
                                "${StatementReconciler.signedAmount(tx).toPlainString()} ${tx.currencyCode}",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun LineRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(right, style = MaterialTheme.typography.bodyMedium)
    }
}
