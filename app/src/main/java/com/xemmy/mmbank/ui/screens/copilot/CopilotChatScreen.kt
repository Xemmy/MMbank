package com.xemmy.mmbank.ui.screens.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.FinancialCopilotQueryHandler
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.CurrencyMath
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One turn of the Copilot conversation. */
data class ChatMessage(val fromUser: Boolean, val text: String)

data class CopilotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val aiConfigured: Boolean = true,
)

class CopilotChatViewModel(private val container: AppContainer) : ViewModel() {

    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val sending = MutableStateFlow(false)

    val uiState: StateFlow<CopilotUiState> = combine(messages, sending) { msgs, isSending ->
        CopilotUiState(messages = msgs, sending = isSending, aiConfigured = container.localAiManager.isConfigured())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CopilotUiState())

    /**
     * Asks [question] against this month's ledger (see [FinancialCopilotQueryHandler]).
     * Aggregated numbers only are sent to the configured AI provider -- never
     * raw transaction text or account numbers.
     */
    fun ask(question: String) {
        val clean = question.trim()
        if (clean.isEmpty() || sending.value) return
        messages.value = messages.value + ChatMessage(fromUser = true, text = clean)
        viewModelScope.launch {
            sending.value = true
            val answer = runCatching {
                if (!container.localAiManager.isConfigured()) {
                    error("AI isn't configured. Enable it and set a provider in Settings > AI.")
                }
                val transactions = container.transactionRepository.observeTransactions().first()
                val rates = container.ratesRepository.observeRates().first()
                val base = container.preferences.defaultCurrency
                val ratesFromBase = rates
                    .filter { it.baseCode.equals(base, ignoreCase = true) }
                    .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val monthStart = today.withDayOfMonth(1)

                val context = FinancialCopilotQueryHandler.buildContext(
                    transactions = transactions,
                    toBase = { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase) },
                    start = monthStart,
                    end = today,
                    currencyCode = base,
                    rangeLabel = "This month",
                    zone = zone,
                )
                val prompt = FinancialCopilotQueryHandler.buildPrompt(clean, context)
                container.localAiManager.engine().generate(prompt).getOrThrow().trim()
            }.getOrElse { e -> "Sorry, I couldn't get an answer: ${e.message}" }
            messages.value = messages.value + ChatMessage(fromUser = false, text = answer)
            sending.value = false
        }
    }
}

/**
 * Conversational Financial Copilot: ask natural-language questions about your
 * own spending ("How much did I spend on Uber this month?"); answers are
 * generated by the configured AI provider from a structured summary of this
 * month's ledger (see [FinancialCopilotQueryHandler]), not raw message text.
 */
@Composable
fun CopilotChatScreen(
    onBack: () -> Unit,
    viewModel: CopilotChatViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
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
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "AI isn't configured yet. Turn it on and set a provider in Settings > AI to chat with the Copilot.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Ask about your spending, e.g. \"How much did I spend on groceries this month?\"",
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
                    items(state.messages) { message -> ChatBubble(message) }
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

            Row(
                Modifier.fillMaxWidth().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask about your spending…") },
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
private fun ChatBubble(message: ChatMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.fromUser) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, end = 4.dp),
            )
        }
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.fromUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
