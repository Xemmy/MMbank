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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.cashback.CashbackCatalog
import com.xemmy.mmbank.domain.cashback.CashbackEngine
import com.xemmy.mmbank.domain.cashback.CashbackSpendCodec
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asMoney

class CashbackViewModel(private val container: AppContainer) : ViewModel() {

    fun loadSpendText(): Map<String, String> =
        CashbackSpendCodec.decode(container.preferences.cashbackSpendRaw)
            .mapValues { it.value.toPlainString() }

    fun persist(spendText: Map<String, String>) {
        val decoded = spendText.mapNotNull { (k, v) -> v.toBigDecimalOrNull()?.let { k to it } }.toMap()
        container.preferences.cashbackSpendRaw = CashbackSpendCodec.encode(decoded)
    }
}

@Composable
fun CashbackScreen(
    viewModel: CashbackViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val spendText = remember { mutableStateMapOf<String, String>().apply { putAll(viewModel.loadSpendText()) } }
    val spendMap = spendText.entries.mapNotNull { (k, v) -> v.toBigDecimalOrNull()?.let { k to it } }.toMap()
    val plan = CashbackEngine.compute(CashbackCatalog.cards, CashbackCatalog.categories, spendMap)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cashback optimizer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Net ${plan.netMonthly.asMoney("AED")}/mo · ${plan.netAnnual.asMoney("AED")}/yr",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (plan.netMonthly.signum() < 0) LedgerColors.debit else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Gross ${plan.grossMonthly.asMoney("AED")}/mo − fees ${plan.monthlyFees.asMoney("AED")}/mo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        if (plan.alerts.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Alerts & tips", style = MaterialTheme.typography.titleSmall)
                        plan.alerts.forEach { alert ->
                            Text(
                                (if (alert.ok) "• " else "⚠ ") + alert.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (alert.ok) MaterialTheme.colorScheme.onSurfaceVariant else LedgerColors.debit,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Enter your monthly spend per category. Each is routed to the card that pays most.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(plan.routings, key = { it.category.key }) { routing ->
            val cat = routing.category
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cat.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                cat.examples,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = spendText[cat.key].orEmpty(),
                            onValueChange = { v ->
                                spendText[cat.key] = v.filter { it.isDigit() || it == '.' }
                                viewModel.persist(spendText)
                            },
                            label = { Text("AED/mo") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp),
                        )
                    }
                    if (routing.card != null && routing.spend.signum() > 0) {
                        Text(
                            "→ ${routing.card.name} · ${"%.1f".format(routing.rate * 100)}% · ${routing.cashback.asMoney("AED")}/mo",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cards in the plan", style = MaterialTheme.typography.titleSmall)
                    plan.cardSummaries.forEach { s ->
                        Text(
                            "${s.card.name}: ${s.cappedCashback.asMoney("AED")}/mo" +
                                (if (s.monthlyFee.signum() > 0) " (fee ${s.monthlyFee.asMoney("AED")}/mo)" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
