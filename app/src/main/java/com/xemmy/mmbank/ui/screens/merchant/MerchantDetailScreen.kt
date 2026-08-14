package com.xemmy.mmbank.ui.screens.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.MerchantProfiler
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.ObsidianCard
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MerchantDetailUiState(
    val merchant: String = "",
    val profile: MerchantProfiler.MerchantProfile? = null,
    val currentCategory: Category? = null,
    val categories: List<Category> = emptyList(),
    /** True when [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine] currently flags this merchant as recurring. */
    val isSubscription: Boolean = false,
)

class MerchantDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val container: AppContainer,
) : ViewModel() {

    /**
     * Nav-arg merchant name. Navigation Compose URL-decodes string path
     * segments itself before populating [SavedStateHandle], so this must
     * NOT be decoded again here — doing so would mangle a merchant name
     * that happens to contain a literal "%".
     */
    private val merchantName: String = savedStateHandle.get<String>("merchant").orEmpty()

    val uiState: StateFlow<MerchantDetailUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.categoryRepository.observeAll(),
        container.subscriptionRepository.observeActive(),
    ) { transactions, categories, subscriptions ->
        val key = merchantName.trim()
        val currentCategoryId = transactions
            .asSequence()
            .filter { it.merchant?.trim()?.equals(key, ignoreCase = true) == true && it.categoryId != null }
            .groupingBy { it.categoryId }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        MerchantDetailUiState(
            merchant = merchantName,
            profile = MerchantProfiler.profile(transactions, merchantName),
            currentCategory = categories.firstOrNull { it.id == currentCategoryId },
            categories = categories,
            isSubscription = subscriptions.any { it.merchant.trim().equals(key, ignoreCase = true) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MerchantDetailUiState(merchant = merchantName))

    /** Confirms [category] for this merchant and backfills every still-uncategorized transaction from it. */
    fun recategorize(category: Category) {
        viewModelScope.launch {
            runCatching { container.categoryRepository.learnMapping(merchantName, category.id) }
                .onFailure { container.recordError("MerchantDetailViewModel.recategorize", it) }
        }
    }

    fun createAndAssignCategory(name: String, colorHex: String, icon: String, monthlyCap: BigDecimal?) {
        viewModelScope.launch {
            runCatching {
                val categoryId = container.categoryRepository.save(
                    Category(name = name, icon = icon, colorHex = colorHex, isCustom = true, monthlyCap = monthlyCap)
                )
                container.categoryRepository.learnMapping(merchantName, categoryId)
            }.onFailure { container.recordError("MerchantDetailViewModel.createAndAssignCategory", it) }
        }
    }
}

/**
 * Per-merchant drill-down: lifetime stats, a monthly spend trend, whether
 * [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine] flags it as
 * recurring, and a one-tap re-categorize that backfills every still-
 * uncategorized transaction from this merchant via the existing
 * [com.xemmy.mmbank.domain.repository.CategoryRepository.learnMapping].
 *
 * Reached from a merchant name tap in [com.xemmy.mmbank.ui.screens.transactions.TransactionDetailSheet]
 * or a subscription row in [com.xemmy.mmbank.ui.screens.subscriptions.SubscriptionsScreen], via the
 * "merchant_detail/{merchant}" route registered in `MainActivity`'s `AppNavHost`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantDetailScreen(
    onBack: () -> Unit,
    viewModel: MerchantDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val profile = state.profile
        if (profile == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No transaction history for this merchant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .obsidianCanvas()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ObsidianCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        StatColumn("Total spent", profile.totalSpent.asMoney(profile.currencyCode), Modifier.weight(1f))
                        StatColumn("Transactions", profile.transactionCount.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        StatColumn("Average", profile.averageAmount.asMoney(profile.currencyCode), Modifier.weight(1f))
                        StatColumn(
                            "Range",
                            "${profile.minAmount.asMoney(profile.currencyCode)} – ${profile.maxAmount.asMoney(profile.currencyCode)}",
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        StatColumn("First seen", profile.firstSeenMillis.asDate(), Modifier.weight(1f))
                        StatColumn("Last seen", profile.lastSeenMillis.asDate(), Modifier.weight(1f))
                    }
                    if (state.isSubscription) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Repeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Flagged as a recurring subscription",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (profile.monthlySeries.isNotEmpty()) {
                ObsidianCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Monthly spend", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        val maxValue = profile.monthlySeries.maxOf { it.total }.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
                        Row(
                            Modifier.fillMaxWidth().height(90.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            profile.monthlySeries.forEach { point ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    val fraction = (point.total.toDouble() / maxValue.toDouble()).toFloat()
                                    MerchantChartBar(fraction)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        point.yearMonth.month.name.take(3),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ObsidianCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        MetaLabel("Category")
                        Text(
                            state.currentCategory?.let { "${it.icon}  ${it.name}" } ?: "Uncategorized",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    OutlinedButton(onClick = { pickerOpen = true }) { Text("Change") }
                }
            }
        }
    }

    if (pickerOpen) {
        CategoryPickerBottomSheet(
            categories = state.categories,
            onSelect = { category ->
                viewModel.recategorize(category)
                pickerOpen = false
            },
            onCreateNew = { name, colorHex, icon, cap ->
                viewModel.createAndAssignCategory(name, colorHex, icon, cap)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MerchantChartBar(fraction: Float) {
    val barHeight = (fraction.coerceIn(0f, 1f) * 70).dp
    Box(
        Modifier
            .width(14.dp)
            .height(maxOf(barHeight, 2.dp))
            .background(
                if (barHeight > 2.dp) ObsidianTokens.debit else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
            ),
    )
}
