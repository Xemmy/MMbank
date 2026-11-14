package com.xemmy.mmbank.ui.screens.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.FinancialCopilotQueryHandler
import com.xemmy.mmbank.domain.intelligence.copilot.CopilotAnswerEngine
import com.xemmy.mmbank.domain.intelligence.copilot.CopilotDecision
import com.xemmy.mmbank.domain.intelligence.copilot.CopilotQueryParser
import com.xemmy.mmbank.domain.intelligence.copilot.CopilotResponder
import com.xemmy.mmbank.domain.intelligence.copilot.CopilotSuggestions
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.screens.dashboard.resolveCategory
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One turn of the Copilot conversation. [onDevice] marks an answer computed
 * on the phone (no AI involved); [drill] is an encoded
 * [com.xemmy.mmbank.domain.filter.ActivityDrillDown] behind "See transactions".
 */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val onDevice: Boolean = false,
    val drill: String? = null,
    /** True for the "here's what I can answer" reply, after which the chips show again. */
    val isHelp: Boolean = false,
)

data class CopilotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val aiConfigured: Boolean = true,
    /** Questions from the user's own data -- see [CopilotSuggestions]. */
    val suggestions: List<String> = emptyList(),
)

class CopilotChatViewModel(private val container: AppContainer) : ViewModel() {

    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val sending = MutableStateFlow(false)

    private val suggestions: StateFlow<List<String>> = combine(
        container.transactionRepository.observeTransactions(),
        container.categoryRepository.observeAll(),
        container.ratesRepository.observeRates(),
    ) { transactions, categories, rates ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = ratesFrom(rates, base)
        CopilotSuggestions.build(
            transactions = transactions,
            categories = categories,
            toBase = { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase) },
            today = LocalDate.now(),
        )
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("CopilotChatViewModel.suggestions", container) { emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<CopilotUiState> = combine(messages, sending, suggestions) { msgs, isSending, chips ->
        CopilotUiState(
            messages = msgs,
            sending = isSending,
            aiConfigured = container.localAiManager.isConfigured(),
            suggestions = chips,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CopilotUiState())

    /**
     * Answers [question]. Questions the phone understands (see
     * [CopilotResponder]) are answered from the ledger right here -- exact,
     * instant, nothing sent anywhere. Anything else goes to the configured AI
     * provider as aggregated numbers for the range the question named, never
     * raw transaction text or account numbers; without AI, the reply lists
     * what can be asked.
     */
    fun ask(question: String) {
        val clean = question.trim()
        if (clean.isEmpty() || sending.value) return
        messages.value = messages.value + ChatMessage(fromUser = true, text = clean)
        viewModelScope.launch {
            sending.value = true
            val reply = runCatching { reply(clean) }
                .getOrElse { e -> ChatMessage(fromUser = false, text = "Sorry, I couldn't get an answer: ${e.message}") }
            messages.value = messages.value + reply
            sending.value = false
        }
    }

    private suspend fun reply(question: String): ChatMessage {
        val transactions = container.transactionRepository.observeTransactions().first()
        val categories = container.categoryRepository.observeAll().first()
        val rates = container.ratesRepository.observeRates().first()
        val base = container.preferences.defaultCurrency
        val ratesFromBase = ratesFrom(rates, base)
        val toBase = { tx: Transaction -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase) }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val aiConfigured = container.localAiManager.isConfigured()
        val merchants = transactions.mapNotNull { it.merchant?.trim()?.takeIf(String::isNotEmpty) }.distinct()

        return when (val decision = withContext(Dispatchers.Default) {
            CopilotResponder.decide(question, categories, merchants, today, aiConfigured)
        }) {
            is CopilotDecision.Local -> {
                val answer = withContext(Dispatchers.Default) {
                    CopilotAnswerEngine.answer(
                        query = decision.query,
                        transactions = transactions,
                        categories = categories,
                        toBase = toBase,
                        currencyCode = base,
                        today = today,
                        zone = zone,
                        formatMoney = { moneyLabel(it, base) },
                    )
                }
                ChatMessage(
                    fromUser = false,
                    text = answer.text,
                    onDevice = true,
                    drill = answer.drillDown?.takeUnless { it.isEmpty }?.encode(),
                )
            }
            is CopilotDecision.AskAi -> {
                val context = FinancialCopilotQueryHandler.buildContext(
                    transactions = transactions,
                    toBase = toBase,
                    start = decision.range.window.start,
                    end = decision.range.window.end,
                    currencyCode = base,
                    rangeLabel = decision.range.label.replaceFirstChar { it.titlecase() },
                    zone = zone,
                    categoryLabelOf = { tx -> resolveCategory(tx.categoryId, categories).name },
                )
                val prompt = FinancialCopilotQueryHandler.buildPrompt(question, context)
                val text = container.localAiManager.engine().generate(prompt).getOrThrow().trim()
                ChatMessage(fromUser = false, text = text)
            }
            CopilotDecision.Help ->
                ChatMessage(fromUser = false, text = CopilotResponder.helpText(aiConfigured), isHelp = true)
        }
    }

    private fun ratesFrom(rates: List<com.xemmy.mmbank.domain.model.ExchangeRate>, base: String): Map<String, Double> =
        rates.filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
}

/**
 * Conversational Financial Copilot: ask about your own money ("How much did I
 * spend on Dining last month?"). Common questions are answered on the phone
 * from the ledger, with no AI needed (see [CopilotQueryParser]); others go to
 * the configured AI provider as aggregated numbers only. Answers can open
 * Activity filtered to exactly the transactions they describe.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CopilotChatScreen(
    onBack: () -> Unit,
    /** Opens Activity with an encoded [com.xemmy.mmbank.domain.filter.ActivityDrillDown]. */
    onOpenActivity: (String) -> Unit = {},
    viewModel: CopilotChatViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    val showChips = state.suggestions.isNotEmpty() && !state.sending &&
        (state.messages.isEmpty() || state.messages.last().isHelp)

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Financial Copilot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.aiConfigured) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Everyday questions are answered right here on your phone. " +
                            "For open-ended ones, set up AI in Settings > Local AI parsing.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Ask about your money, e.g. \"How much did I spend on groceries last month?\" " +
                            "or \"Compare this month with last month\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages) { message ->
                        ChatBubble(message, onOpenActivity = onOpenActivity)
                    }
                    if (state.sending) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (showChips) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.suggestions) { chip ->
                        SuggestionChip(
                            onClick = { viewModel.ask(chip) },
                            label = { Text(chip) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask about your money…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = input.isNotBlank() && !state.sending,
                    onClick = {
                        viewModel.ask(input)
                        input = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onOpenActivity: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.fromUser) {
            Icon(
                if (message.onDevice) Icons.Filled.PhoneAndroid else Icons.Filled.SmartToy,
                contentDescription = if (message.onDevice) "Answered on this phone" else null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, end = 4.dp),
            )
        }
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.fromUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (message.onDevice) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Answered on this phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                message.drill?.let { drill ->
                    TextButton(
                        onClick = { onOpenActivity(drill) },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("See transactions") }
                }
            }
        }
    }
}
