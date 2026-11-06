package com.xemmy.mmbank.ui.screens.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.ai.AiSelfHealingRuleGenerator
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.parser.RuleHistoryTester
import com.xemmy.mmbank.ui.AppViewModelProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Learned merchant rules as (merchant, what it maps to) pairs, for display. */
data class LearnedRules(
    val kinds: List<Pair<String, String>> = emptyList(),
    val accounts: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = kinds.isEmpty() && accounts.isEmpty()
}

/** Name prefix every AI-generated candidate rule carries; see [AiSelfHealingRuleGenerator.PENDING_RULE_PREFIX]. */
private val SUGGESTED_NAME_PREFIX = "${AiSelfHealingRuleGenerator.PENDING_RULE_PREFIX}."

class RulesViewModel(private val container: AppContainer) : ViewModel() {

    val rules: StateFlow<List<ParsingRule>> = container.parsingRuleRepository.observeAll()
        .catch { e ->
            container.recordError("RulesViewModel", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Raw message text of every recorded transaction -- the corpus "Test against history" runs a candidate over. */
    val historicalMessages: StateFlow<List<String>> = container.transactionRepository.observeTransactions()
        .map { txs -> txs.mapNotNull { it.rawMessage } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What the app learned from the user's corrections, so they can see and
     * undo it: merchant -> kind ("Remember for this merchant") and merchant ->
     * account (assigning a transaction to an account).
     */
    val learned: StateFlow<LearnedRules> = combine(
        container.ledgerRepository.observeMerchantKinds(),
        container.merchantAccountRepository.observeMappings(),
        container.accountRepository.observeActiveReal(),
    ) { kinds, accounts, accountList ->
        val names = accountList.associate { it.id to it.name }
        LearnedRules(
            kinds = kinds.map { it.merchantPattern to it.kind.label },
            accounts = accounts.map { it.merchantPattern to (names[it.accountId] ?: "an archived account") },
        )
    }
        .catch { e ->
            container.recordError("RulesViewModel.learned", e)
            emit(LearnedRules())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearnedRules())

    fun forgetKind(merchant: String) {
        viewModelScope.launch {
            runCatching { container.ledgerRepository.forgetMerchantKind(merchant) }
                .onFailure { container.recordError("RulesViewModel.forgetKind", it) }
        }
    }

    fun forgetAccount(merchant: String) {
        viewModelScope.launch {
            runCatching { container.merchantAccountRepository.forget(merchant) }
                .onFailure { container.recordError("RulesViewModel.forgetAccount", it) }
        }
    }

    fun toggleEnabled(rule: ParsingRule) {
        viewModelScope.launch {
            runCatching { container.parsingRuleRepository.save(rule.copy(enabled = !rule.enabled)) }
                .onFailure { container.recordError("RulesViewModel.toggleEnabled", it) }
        }
    }

    /** How many already-recorded transactions this candidate's pattern would also match. */
    fun historyMatchCount(rule: ParsingRule): Int =
        RuleHistoryTester.countMatches(rule, historicalMessages.value, container.preferences.defaultCurrency)

    /** Turns a suggested candidate into an active rule -- the only path that ever enables AI-generated output. */
    fun approve(rule: ParsingRule) {
        viewModelScope.launch {
            runCatching { container.parsingRuleRepository.save(rule.copy(enabled = true)) }
                .onFailure { container.recordError("RulesViewModel.approve", it) }
        }
    }

    /** Rejects a suggestion outright, removing it from the candidate queue. */
    fun discard(rule: ParsingRule) {
        viewModelScope.launch {
            runCatching { container.parsingRuleRepository.delete(rule.id) }
                .onFailure { container.recordError("RulesViewModel.discard", it) }
        }
    }
}

/**
 * Custom Rule Engine: user-defined regex mappings for bank notification
 * layouts the built-ins don't cover — editable without recompiling the APK.
 */
@Composable
fun RulesScreen(
    onEditRule: (Long) -> Unit,
    onNewRule: () -> Unit,
    viewModel: RulesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val suggested = rules.filter { it.name.startsWith(SUGGESTED_NAME_PREFIX) }
    val custom = rules.filterNot { it.name.startsWith(SUGGESTED_NAME_PREFIX) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNewRule) {
                Icon(Icons.Filled.Add, contentDescription = "New rule")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (suggested.isNotEmpty()) {
                item {
                    Text(
                        "Suggested rules (${suggested.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Text(
                        "The AI recovered a transaction from a message the rules missed and built a pattern " +
                            "for it. Nothing here runs until you approve it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(suggested, key = { it.id }) { rule ->
                    SuggestedRuleCard(
                        rule = rule,
                        onTest = { viewModel.historyMatchCount(rule) },
                        onApprove = { viewModel.approve(rule) },
                        onDiscard = { viewModel.discard(rule) },
                        onEdit = { onEditRule(rule.id) },
                    )
                }
            }
            item {
                Text(
                    "Custom parsing rules run before the built-in English/Arabic rules. " +
                        "A rule needs a named group (?<amount>...) and may add (?<currency>...) " +
                        "and (?<merchant>...).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(custom, key = { it.id }) { rule ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEditRule(rule.id) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                rule.pattern,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            Text(
                                "${rule.direction.name} · priority ${rule.priority}" +
                                    (rule.defaultCurrency?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.toggleEnabled(rule) },
                        )
                    }
                }
            }
            if (!learned.isEmpty) {
                item {
                    Text(
                        "Learned from your corrections",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item {
                    Text(
                        "New transactions from these merchants are classified and filed automatically. " +
                            "Forgetting one stops that; past transactions stay as they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(learned.kinds, key = { "kind_${it.first}" }) { (merchant, kind) ->
                    LearnedRuleRow(merchant, "is a $kind", onForget = { viewModel.forgetKind(merchant) })
                }
                items(learned.accounts, key = { "account_${it.first}" }) { (merchant, account) ->
                    LearnedRuleRow(merchant, "files under $account", onForget = { viewModel.forgetAccount(merchant) })
                }
            }
        }
    }
}

@Composable
private fun LearnedRuleRow(merchant: String, meaning: String, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(merchant, style = MaterialTheme.typography.bodyMedium)
            Text(meaning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onForget) { Text("Forget") }
    }
}

/**
 * A pending AI-generated candidate awaiting human review. Distinct from an
 * ordinary custom rule's card: no enabled Switch (a suggestion is only ever
 * turned on via [onApprove], never silently), a sparkle icon, its sample
 * message, an on-demand "Test against history" match count, and Approve /
 * Discard actions. Tapping the card still opens the full editor for a closer
 * look or manual tweaks before approving.
 */
@Composable
private fun SuggestedRuleCard(
    rule: ParsingRule,
    onTest: () -> Int,
    onApprove: () -> Unit,
    onDiscard: () -> Unit,
    onEdit: () -> Unit,
) {
    var matchCount by remember(rule.id) { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Text("Suggested from AI", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                rule.pattern,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            rule.sampleText?.let { sample ->
                Text(
                    "\"$sample\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                "${rule.direction.name}" + (rule.defaultCurrency?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            matchCount?.let { count ->
                Text(
                    if (count == 0) {
                        "Matches no other recorded transaction."
                    } else {
                        "Also matches $count other recorded transaction${if (count == 1) "" else "s"}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { matchCount = onTest() }) { Text("Test against history") }
                Button(onClick = onApprove) { Text("Approve") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}
