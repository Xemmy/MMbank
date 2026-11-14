package com.xemmy.mmbank.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers

data class SearchUiState(val query: String = "", val results: List<SearchResult> = emptyList())

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(container: AppContainer) : ViewModel() {

    val query = MutableStateFlow("")

    private data class Data(val transactions: List<Transaction>, val accounts: List<Account>, val rules: List<ParsingRule>)

    private val data = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.parsingRuleRepository.observeAll(),
    ) { transactions, accounts, rules -> Data(transactions, accounts, rules) }

    val uiState: StateFlow<SearchUiState> = combine(query.debounce(150), data) { q, d ->
        SearchUiState(
            query = q,
            results = GlobalSearch.search(q, d.transactions, d.accounts, d.rules, SearchDestinations.all),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpen: (SearchTarget) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Search merchants, accounts, rules, settings…") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.query.value = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
        }

        val hint = when {
            query.trim().length < GlobalSearch.MIN_QUERY_CHARS ->
                "Type at least ${GlobalSearch.MIN_QUERY_CHARS} letters. A number finds transactions with exactly that amount."
            state.results.isEmpty() -> "Nothing matches “${query.trim()}”."
            else -> null
        }
        if (hint != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
                var previous: SearchKind? = null
                state.results.forEachIndexed { index, result ->
                    val showHeader = result.kind != previous
                    previous = result.kind
                    item(key = "r$index") {
                        Column(Modifier.fillMaxWidth()) {
                            if (showHeader) {
                                Text(
                                    result.kind.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            Column(
                                Modifier.fillMaxWidth().clickable { onOpen(result.target) }.padding(vertical = 8.dp),
                            ) {
                                Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (result.subtitle.isNotBlank()) {
                                    Text(
                                        result.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
