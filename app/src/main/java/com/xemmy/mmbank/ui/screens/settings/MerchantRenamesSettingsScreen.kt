package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.xemmy.mmbank.domain.model.MerchantAlias
import com.xemmy.mmbank.ui.AppViewModelProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MerchantRenamesViewModel(private val container: AppContainer) : ViewModel() {
    val aliases: StateFlow<List<MerchantAlias>> = container.merchantAliasRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(alias: MerchantAlias) {
        viewModelScope.launch {
            runCatching { container.merchantAliasRepository.delete(alias.fromKey) }
                .onFailure { container.recordError("MerchantRenamesViewModel.delete", it) }
        }
    }
}

/** Settings > Merchant renames: the "also rename future messages" rules, each deletable. */
@Composable
fun MerchantRenamesSettingsScreen(
    onBack: () -> Unit,
    viewModel: MerchantRenamesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    SettingsSubpage(title = "Merchant renames", onBack = onBack) {
        Text(
            "When a new message arrives from one of these names, it is filed under the name you chose. " +
                "Deleting a rule does not change transactions already renamed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (aliases.isEmpty()) {
            Text(
                "No renames yet. Use Rename merchant on a subscription's charges and tick \"Also rename future messages\".",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        aliases.forEach { alias ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(alias.toName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "from \"${alias.fromKey}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.delete(alias) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete rename of ${alias.fromKey}")
                    }
                }
            }
        }
    }
}
