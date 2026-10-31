package com.xemmy.mmbank.ui.screens.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.RuleCompiler
import com.xemmy.mmbank.domain.parser.TransactionParser
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asMoney
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RuleEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    private val ruleId: Long = savedStateHandle["ruleId"] ?: -1L
    val isEditing: Boolean get() = ruleId > 0

    var name by mutableStateOf("")
    var pattern by mutableStateOf("")
    var senderPattern by mutableStateOf("")
    var direction by mutableStateOf(TransactionDirection.DEBIT)
    var defaultCurrency by mutableStateOf("")
    var priorityText by mutableStateOf("100")
    var sampleText by mutableStateOf("")
    /** Account matched transactions are pinned to; null = detect from the message. */
    var assignAccountId by mutableStateOf<Long?>(null)
    /** Kind matched transactions get; null = classify automatically. */
    var assignKind by mutableStateOf<TransactionKind?>(null)
    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            // Real accounts only: a rule pinned to a demo account would dangle
            // when Demo mode is switched off and its accounts are deleted.
            runCatching { accounts = container.accountRepository.observeActiveReal().first() }
            if (isEditing) {
                container.parsingRuleRepository.getById(ruleId)?.let { rule ->
                    name = rule.name
                    pattern = rule.pattern
                    senderPattern = rule.senderPattern.orEmpty()
                    direction = rule.direction
                    defaultCurrency = rule.defaultCurrency.orEmpty()
                    priorityText = rule.priority.toString()
                    sampleText = rule.sampleText.orEmpty()
                    assignAccountId = rule.assignAccountId
                    assignKind = rule.assignKind
                }
            }
            loaded = true
        }
    }

    val assignedAccountName: String?
        get() = accounts.firstOrNull { it.id == assignAccountId }?.name

    val patternError: String? get() = RuleCompiler.validate(pattern)

    /** Runs the current pattern against the sample text exactly as the live pipeline would. */
    fun testAgainstSample(): ParsedTransaction? {
        val rule = buildRule() ?: return null
        val compiled = RuleCompiler.compile(rule) ?: return null
        return TransactionParser(listOf(compiled), container.preferences.defaultCurrency).parse(
            CapturedMessage(
                source = TransactionSource.MANUAL,
                sender = null,
                title = null,
                body = sampleText,
                timestampMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun buildRule(): ParsingRule? {
        if (name.isBlank() || patternError != null) return null
        return ParsingRule(
            id = if (isEditing) ruleId else 0,
            name = name.trim(),
            pattern = pattern,
            senderPattern = senderPattern.trim().takeIf { it.isNotEmpty() },
            direction = direction,
            defaultCurrency = defaultCurrency.trim().uppercase().takeIf { it.isNotEmpty() },
            priority = priorityText.toIntOrNull() ?: 100,
            sampleText = sampleText.takeIf { it.isNotBlank() },
            assignAccountId = assignAccountId,
            assignKind = assignKind,
        )
    }

    fun save(onDone: () -> Unit) {
        val rule = buildRule() ?: return
        viewModelScope.launch {
            container.parsingRuleRepository.save(rule)
            // Pin the account onto the transactions this rule already captured
            // but left unassigned (e.g. Tabby messages carry no card number),
            // so setting "Assign matches to" fixes history, not just new capture.
            rule.assignAccountId?.let { accountId ->
                runCatching { container.transactionRepository.assignAccountForRule(rule.name, accountId) }
                    .onFailure { container.recordError("RuleEditor.backfillAssign", it) }
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (!isEditing) return onDone()
        viewModelScope.launch {
            container.parsingRuleRepository.delete(ruleId)
            onDone()
        }
    }
}

@Composable
fun RuleEditorScreen(
    onDone: () -> Unit,
    viewModel: RuleEditorViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    if (!viewModel.loaded) return

    val mono = TextStyle(fontFamily = FontFamily.Monospace)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (viewModel.isEditing) "Edit rule" else "New rule",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = viewModel.name,
            onValueChange = { viewModel.name = it },
            label = { Text("Rule name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.pattern,
            onValueChange = { viewModel.pattern = it },
            label = { Text("Regex pattern — must contain (?<amount>...)") },
            textStyle = mono,
            minLines = 3,
            isError = viewModel.pattern.isNotBlank() && viewModel.patternError != null,
            supportingText = {
                val error = viewModel.patternError
                if (viewModel.pattern.isNotBlank() && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Optional groups: (?<currency>...) and (?<merchant>...)")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.senderPattern,
            onValueChange = { viewModel.senderPattern = it },
            label = { Text("Sender filter regex (optional — SMS sender / app package)") },
            textStyle = mono,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransactionDirection.entries.forEach { entry ->
                FilterChip(
                    selected = viewModel.direction == entry,
                    onClick = { viewModel.direction = entry },
                    label = { Text(entry.name) },
                )
            }
        }

        // Default assign: pin every match of this rule to one account. Wins
        // over the card-number detection — made for messages that never carry
        // an account number (e.g. Tabby Card vs Tabby Cash).
        var accountMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { accountMenuOpen = true }) {
                Text("Assign matches to: " + (viewModel.assignedAccountName ?: "detect from message"))
            }
            DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Detect from message (default)") },
                    onClick = {
                        accountMenuOpen = false
                        viewModel.assignAccountId = null
                    },
                )
                viewModel.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            accountMenuOpen = false
                            viewModel.assignAccountId = account.id
                        },
                    )
                }
            }
        }

        // Default kind: e.g. a rule written for the bank's "card payment"
        // message marks every match as a card payment, so it never counts as
        // spending. Wins over the automatic classification.
        var kindMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { kindMenuOpen = true }) {
                Text("Matches are: " + (viewModel.assignKind?.label ?: "detect automatically"))
            }
            DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Detect automatically (default)") },
                    onClick = {
                        kindMenuOpen = false
                        viewModel.assignKind = null
                    },
                )
                TransactionKind.entries
                    .filter { it.fixedDirection == null || it.fixedDirection == viewModel.direction }
                    .forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                kindMenuOpen = false
                                viewModel.assignKind = kind
                            },
                        )
                    }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = viewModel.defaultCurrency,
                onValueChange = { viewModel.defaultCurrency = it.uppercase().take(3) },
                label = { Text("Fallback currency") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = viewModel.priorityText,
                onValueChange = { viewModel.priorityText = it },
                label = { Text("Priority") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Text("Rule tester", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = viewModel.sampleText,
            onValueChange = { viewModel.sampleText = it },
            label = { Text("Paste a real bank message to test") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        // Live preview: recompiled on every keystroke, same code path as the services.
        val result = remember(
            viewModel.pattern, viewModel.sampleText, viewModel.direction,
            viewModel.defaultCurrency, viewModel.name,
        ) {
            if (viewModel.sampleText.isBlank()) null else runCatching { viewModel.testAgainstSample() }.getOrNull()
        }

        if (viewModel.sampleText.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (result == null) {
                        Text("No match", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Match ✓", color = LedgerColors.credit, style = MaterialTheme.typography.titleSmall)
                        Text("Amount: ${result.amount.asMoney(result.currencyCode)}")
                        Text("Direction: ${result.direction.name}")
                        Text("Merchant: ${result.merchant ?: "—"}")
                        Text("Account: ${viewModel.assignedAccountName ?: "detected from message"}")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.save(onDone) },
                enabled = viewModel.name.isNotBlank() && viewModel.patternError == null,
                modifier = Modifier.weight(1f),
            ) { Text("Save rule") }
            if (viewModel.isEditing) {
                OutlinedButton(
                    onClick = { viewModel.delete(onDone) },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete") }
            } else {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}
