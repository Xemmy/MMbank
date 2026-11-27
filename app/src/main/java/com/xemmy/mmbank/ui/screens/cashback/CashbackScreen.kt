package com.xemmy.mmbank.ui.screens.cashback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.cashback.CashbackCatalog
import com.xemmy.mmbank.domain.cashback.CashbackEngine
import com.xemmy.mmbank.domain.cashback.CashbackSpendCodec
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDate
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RealCashbackUiState(
    val totalsByCurrencyAllTime: Map<String, BigDecimal> = emptyMap(),
    val totalsByCurrencyThisMonth: Map<String, BigDecimal> = emptyMap(),
    val recent: List<Transaction> = emptyList(),
)

class CashbackViewModel(private val container: AppContainer) : ViewModel() {

    fun loadSpendText(): Map<String, String> =
        CashbackSpendCodec.decode(container.preferences.cashbackSpendRaw)
            .mapValues { it.value.toPlainString() }

    fun persist(spendText: Map<String, String>) {
        val decoded = spendText.mapNotNull { (k, v) -> v.toBigDecimalOrNull()?.let { k to it } }.toMap()
        container.preferences.cashbackSpendRaw = CashbackSpendCodec.encode(decoded)
    }

    /** Real cashback actually captured from bank/fintech messages (e.g. Tabby+ payouts). */
    val realCashback: StateFlow<RealCashbackUiState> =
        container.transactionRepository.observeTransactions()
            .map { txns ->
                val earned = txns.filter { it.category == TransactionCategory.CASHBACK_EARNED }
                val currentMonth = YearMonth.now(ZoneId.systemDefault())
                val thisMonth = earned.filter {
                    YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneId.systemDefault())) == currentMonth
                }
                RealCashbackUiState(
                    totalsByCurrencyAllTime = earned.groupBy { it.currencyCode }
                        .mapValues { (_, v) -> v.sumOf { it.amount } },
                    totalsByCurrencyThisMonth = thisMonth.groupBy { it.currencyCode }
                        .mapValues { (_, v) -> v.sumOf { it.amount } },
                    recent = earned.sortedByDescending { it.timestampMillis }.take(10),
                )
            }
            .catch { e ->
                container.recordError("CashbackViewModel", e)
                emit(RealCashbackUiState())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealCashbackUiState())
}

@Composable
fun CashbackScreen(
    viewModel: CashbackViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val spendText = remember { mutableStateMapOf<String, String>().apply { putAll(viewModel.loadSpendText()) } }
    val spendMap = spendText.entries.mapNotNull { (k, v) -> v.toBigDecimalOrNull()?.let { k to it } }.toMap()
    val plan = CashbackEngine.compute(CashbackCatalog.cards, CashbackCatalog.categories, spendMap)
    val real by viewModel.realCashback.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "NET CASHBACK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            moneyLabel(plan.netMonthly, "AED"),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (plan.netMonthly.signum() < 0) LedgerColors.debit else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            " /month",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    Text(
                        "${moneyLabel(plan.netAnnual, "AED")} /year · gross ${moneyLabel(plan.grossMonthly, "AED")} − fees ${moneyLabel(plan.monthlyFees, "AED")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }

        if (real.totalsByCurrencyAllTime.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Real cashback earned", style = MaterialTheme.typography.bodySmall)
                        real.totalsByCurrencyAllTime.forEach { (cur, total) ->
                            Text("All-time: ${moneyLabel(total, cur)}", style = MaterialTheme.typography.labelSmall)
                        }
                        real.totalsByCurrencyThisMonth.forEach { (cur, total) ->
                            Text(
                                "This month: ${moneyLabel(total, cur)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(real.recent, key = { "cashback_${it.id}" }) { tx ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                BankNames.displayName(tx.sender) ?: "Unknown",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                tx.timestampMillis.asDate(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(moneyLabel(tx.amount, tx.currencyCode), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val warnings = plan.alerts.filter { !it.ok }
        if (warnings.isNotEmpty()) {
            items(warnings, key = { "warning_${it.message}" }) { alert ->
                Text(
                    "⚠ ${alert.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LedgerColors.debit,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item {
            Text(
                "Monthly spend per category — each is routed to the card that pays the most.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }

        items(plan.routings, key = { "routing_${it.category.key}" }) { routing ->
            val cat = routing.category
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(cat.label, style = MaterialTheme.typography.bodySmall)
                            Text(
                                cat.examples,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        OutlinedTextField(
                            value = spendText[cat.key].orEmpty(),
                            onValueChange = { v ->
                                spendText[cat.key] = v.filter { it.isDigit() || it == '.' }
                                viewModel.persist(spendText)
                            },
                            placeholder = { Text("0", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(90.dp),
                        )
                    }
                    if (routing.card != null && routing.spend.signum() > 0) {
                        Text(
                            "→ ${routing.card.name} · ${formatRate(routing.rate)} · ${moneyLabel(routing.cashback, "AED")}/mo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (plan.cardSummaries.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Cards in the plan", style = MaterialTheme.typography.bodySmall)
                        plan.cardSummaries.forEach { s ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    s.card.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    moneyLabel(s.cappedCashback, "AED") +
                                        (if (s.monthlyFee.signum() > 0) " − fee ${moneyLabel(s.monthlyFee, "AED")}" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Pay every card in full monthly — interest wipes out all cashback. " +
                    "Use a 0% FX card for foreign currency.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatRate(rate: Double): String =
    String.format(Locale.US, "%.1f%%", rate * 100)
