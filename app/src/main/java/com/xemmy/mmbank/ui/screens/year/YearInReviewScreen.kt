package com.xemmy.mmbank.ui.screens.year

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.xemmy.mmbank.domain.report.YearInReview
import com.xemmy.mmbank.domain.report.YearInReviewBuilder
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.asMoneyWhole
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class YearUiState(val year: Int, val currency: String, val review: YearInReview?, val loaded: Boolean)

class YearInReviewViewModel(private val container: AppContainer) : ViewModel() {

    private val year = MutableStateFlow(LocalDate.now().year)

    val uiState: StateFlow<YearUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.categoryRepository.observeAll(),
        container.ratesRepository.observeRates(),
        year,
    ) { transactions, categories, rates, selected ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
        YearUiState(
            year = selected,
            currency = base,
            review = YearInReviewBuilder.build(
                year = selected,
                transactions = transactions,
                categoryNames = categories.associate { it.id to it.name },
                currency = base,
                toBase = { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase) },
            ),
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YearUiState(LocalDate.now().year, "", null, false))

    fun previousYear() { year.value = year.value - 1 }

    /** Never past the current year: there is nothing to review yet. */
    fun nextYear() { if (year.value < LocalDate.now().year) year.value = year.value + 1 }
}

@Composable
fun YearInReviewScreen(
    onBack: () -> Unit,
    viewModel: YearInReviewViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val review = state.review
    val money = { v: Double -> v.asMoneyWhole(state.currency) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Year in review", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::previousYear) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
            }
            Text(state.year.toString(), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = viewModel::nextYear) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year")
            }
        }

        if (review == null) {
            Text(
                if (state.loaded) "Nothing recorded in ${state.year}." else "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "headline") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Spent", style = MaterialTheme.typography.labelMedium)
                        Text(money(review.spent), style = MaterialTheme.typography.headlineMedium)
                        review.spendChangePercent?.let { pct ->
                            val dir = if (pct >= 0) "more" else "less"
                            Text(
                                "${"%.0f".format(Locale.US, kotlin.math.abs(pct))}% $dir than ${state.year - 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Received ${money(review.received)} · Net ${money(review.net)}" +
                                (review.savingsRate?.let { " · Saved ${"%.0f".format(Locale.US, it * 100)}%" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item(key = "facts") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Fact("Transactions", review.transactionCount.toString())
                        review.busiestMonth?.let { Fact("Busiest month", it.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) }
                        review.quietestMonth?.let { Fact("Quietest month", it.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) }
                        review.biggestExpense?.let { Fact("Biggest expense", "${it.merchant} · ${money(it.amount)} · ${it.date}") }
                        Fact("Days with no spending", review.noSpendDays.toString())
                    }
                }
            }
            item(key = "months") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Spending by month", style = MaterialTheme.typography.titleSmall)
                        val peak = (review.monthlySpend.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                        review.monthlySpend.forEachIndexed { index, value ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    java.time.Month.of(index + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                LinearProgressIndicator(
                                    progress = { (value / peak).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.weight(1f),
                                )
                                Text(money(value), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (review.topCategories.isNotEmpty()) item(key = "categories") { RankedCard("Top categories", review.topCategories, money) }
            if (review.topMerchants.isNotEmpty()) item(key = "merchants") { RankedCard("Top merchants", review.topMerchants, money) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun RankedCard(title: String, rows: List<YearInReview.Ranked>, money: (Double) -> String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            rows.forEach { Fact(if (it.count > 0) "${it.name} (${it.count})" else it.name, money(it.amount)) }
        }
    }
}
