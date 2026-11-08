package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.DebtCandidates
import com.xemmy.mmbank.domain.account.DebtPayoffSimulator
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.ObsidianCard
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** One debt as the planner shows it: what the account says, and what the user typed over it. */
data class PlannerDebtRow(
    val candidate: DebtCandidates.Candidate,
    val aprInput: String,
    val minimumInput: String,
)

data class DebtPlannerUiState(
    val loaded: Boolean = false,
    val baseCurrency: String = "",
    val rows: List<PlannerDebtRow> = emptyList(),
    val budgetInput: String = "",
    /** What the minimums add up to, in the base currency. */
    val minimumBudget: BigDecimal = BigDecimal.ZERO,
    val avalanche: DebtPayoffSimulator.Plan? = null,
    val snowball: DebtPayoffSimulator.Plan? = null,
    /** Debts left out because their currency has no exchange rate. */
    val unconverted: List<String> = emptyList(),
    /** Debts simulated at 0% because no rate is known. */
    val unknownRate: List<String> = emptyList(),
)

/**
 * Builds the planner from candidates, the user's overrides and their budget.
 * Every amount is converted to [baseCurrency] (a debt with no rate is left
 * out and named); a debt with no known rate is simulated at 0% and named.
 * Pure and top-level so it's JVM-testable.
 */
internal fun computeDebtPlan(
    candidates: List<DebtCandidates.Candidate>,
    aprOverrides: Map<Long, String>,
    minimumOverrides: Map<Long, String>,
    budgetInput: String,
    baseCurrency: String,
    ratesFromBase: Map<String, Double>,
): DebtPlannerUiState {
    val unconverted = mutableListOf<String>()
    val unknownRate = mutableListOf<String>()
    val rows = candidates.map { c ->
        PlannerDebtRow(
            candidate = c,
            aprInput = aprOverrides[c.accountId] ?: c.aprPercent?.let(::plainNumber).orEmpty(),
            minimumInput = minimumOverrides[c.accountId] ?: c.minimumPayment.stripTrailingZeros().toPlainString(),
        )
    }
    val debts = rows.mapNotNull { row ->
        val c = row.candidate
        fun toBase(amount: BigDecimal): BigDecimal? =
            CurrencyMath.toBase(amount.toDouble(), c.currencyCode, baseCurrency, ratesFromBase)
                ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
        val owed = toBase(c.owed)
        val minimum = row.minimumInput.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let(::toBase)
            ?: toBase(c.minimumPayment)
        if (owed == null || minimum == null) {
            unconverted += c.name
            return@mapNotNull null
        }
        val apr = row.aprInput.toDoubleOrNull()?.takeIf { it >= 0.0 }
        if (apr == null) unknownRate += c.name
        DebtPayoffSimulator.Debt(c.accountId, c.name, owed, apr ?: 0.0, minimum)
    }
    val minimumBudget = DebtPayoffSimulator.minimumBudget(debts)
    val budget = budgetInput.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val (avalanche, snowball) = if (budget != null && debts.isNotEmpty()) {
        DebtPayoffSimulator.compare(debts, budget)
    } else {
        null to null
    }
    return DebtPlannerUiState(
        loaded = true,
        baseCurrency = baseCurrency,
        rows = rows,
        budgetInput = budgetInput,
        minimumBudget = minimumBudget,
        avalanche = avalanche,
        snowball = snowball,
        unconverted = unconverted,
        unknownRate = unknownRate,
    )
}

/** A starting budget: the minimums plus 20%, rounded up to the next 50. */
internal fun suggestedBudget(minimumBudget: BigDecimal): BigDecimal {
    if (minimumBudget.signum() <= 0) return BigDecimal.ZERO
    val raised = minimumBudget * BigDecimal("1.2")
    return raised.divide(BigDecimal(50), 0, RoundingMode.CEILING) * BigDecimal(50)
}

/**
 * One sentence comparing the two strategies. Avalanche never costs more
 * interest; Snowball's case is the quick first win.
 */
internal fun compareStrategies(
    avalanche: DebtPayoffSimulator.Plan,
    snowball: DebtPayoffSimulator.Plan,
    money: (BigDecimal) -> String,
): String? {
    if (avalanche.outcome != DebtPayoffSimulator.Outcome.PAID_OFF || snowball.outcome != DebtPayoffSimulator.Outcome.PAID_OFF) return null
    val saving = snowball.totalInterest - avalanche.totalInterest
    val firstSnow = snowball.debts.firstOrNull()
    val firstAval = avalanche.debts.firstOrNull()
    val quickWin = if (firstSnow != null && firstAval != null && firstSnow.id != firstAval.id &&
        (firstSnow.payoffMonth ?: Int.MAX_VALUE) < (firstAval.payoffMonth ?: Int.MAX_VALUE)
    ) {
        " Snowball clears ${firstSnow.name} first, in month ${firstSnow.payoffMonth}."
    } else {
        ""
    }
    return if (saving.signum() > 0) {
        "Avalanche saves ${money(saving)} in interest.$quickWin"
    } else {
        "Both cost the same here.$quickWin"
    }
}

private fun plainNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

class DebtPlannerViewModel(private val container: AppContainer) : ViewModel() {

    private val aprOverrides = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val minimumOverrides = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val budget = MutableStateFlow(container.preferences.debtPlannerBudget)

    private data class Inputs(val apr: Map<Long, String>, val minimum: Map<Long, String>, val budget: String)

    private val inputs = combine(aprOverrides, minimumOverrides, budget) { a, m, b -> Inputs(a, m, b) }

    val uiState: StateFlow<DebtPlannerUiState> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeLedgerEntries(),
        container.ratesRepository.observeRates(),
        inputs,
    ) { accounts, entries, rates, input ->
        val base = container.preferences.defaultCurrency
        val candidates = DebtCandidates.build(accounts, AccountBalanceCalculator.balances(accounts, entries))
        val ratesFromBase = ratesFrom(rates, base)
        val first = computeDebtPlan(candidates, input.apr, input.minimum, input.budget, base, ratesFromBase)
        if (input.budget.isBlank() && first.minimumBudget.signum() > 0) {
            // No budget yet: start from a suggestion rather than an empty plan.
            computeDebtPlan(candidates, input.apr, input.minimum, suggestedBudget(first.minimumBudget).toPlainString(), base, ratesFromBase)
        } else {
            first
        }
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("DebtPlannerViewModel", container) { DebtPlannerUiState(loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtPlannerUiState())

    fun setApr(accountId: Long, input: String) {
        aprOverrides.value = aprOverrides.value + (accountId to input.filter { it.isDigit() || it == '.' })
    }

    fun setMinimum(accountId: Long, input: String) {
        minimumOverrides.value = minimumOverrides.value + (accountId to input.filter { it.isDigit() || it == '.' })
    }

    fun setBudget(input: String) {
        val clean = input.filter { it.isDigit() || it == '.' }
        budget.value = clean
        container.preferences.debtPlannerBudget = clean
    }

    private fun ratesFrom(rates: List<ExchangeRate>, base: String): Map<String, Double> =
        rates.filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
}

/**
 * Debt payoff planner: every card and loan that owes money, a monthly budget,
 * and the two classic strategies side by side -- when you'd be debt-free and
 * what it costs in interest. Rates and minimums come from the accounts and
 * can be typed over here (nothing is saved back to the account).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtPlannerScreen(
    onBack: () -> Unit,
    viewModel: DebtPlannerViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val base = state.baseCurrency
    val money = { v: BigDecimal -> v.asMoney(base) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pay off debts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().obsidianCanvas().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded && state.rows.isEmpty()) {
                item {
                    Text(
                        "No card or loan owes anything right now. Nothing to plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            item {
                Text(
                    "Every card and loan that owes money, with its yearly rate and monthly minimum. " +
                        "Fix anything that's off, set what you can put towards debt each month, and " +
                        "compare the two classic ways to pay it down.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.rows, key = { it.candidate.accountId }) { row ->
                DebtRowCard(
                    row = row,
                    onApr = { viewModel.setApr(row.candidate.accountId, it) },
                    onMinimum = { viewModel.setMinimum(row.candidate.accountId, it) },
                )
            }

            item(key = "budget") {
                OutlinedTextField(
                    value = state.budgetInput,
                    onValueChange = viewModel::setBudget,
                    label = { Text("Monthly budget for debts ($base)") },
                    supportingText = { Text("Minimums add up to ${money(state.minimumBudget)}.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val avalanche = state.avalanche
            val snowball = state.snowball
            if (avalanche != null && snowball != null) {
                compareStrategies(avalanche, snowball, money)?.let { line ->
                    item(key = "compare") {
                        Text(line, style = MaterialTheme.typography.titleSmall, color = ObsidianTokens.credit)
                    }
                }
                item(key = "avalanche") {
                    PlanCard("Avalanche: highest rate first", avalanche, money, best = avalanche.totalInterest <= snowball.totalInterest)
                }
                item(key = "snowball") {
                    PlanCard("Snowball: smallest balance first", snowball, money, best = false)
                }
            }

            if (state.unknownRate.isNotEmpty()) {
                item {
                    Text(
                        "No rate known for ${state.unknownRate.joinToString()}: treated as 0%. Type the yearly rate above for a real plan.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ObsidianTokens.textMeta,
                    )
                }
            }
            if (state.unconverted.isNotEmpty()) {
                item {
                    Text(
                        "Left out (no exchange rate to $base yet): ${state.unconverted.joinToString()}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ObsidianTokens.textMeta,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebtRowCard(row: PlannerDebtRow, onApr: (String) -> Unit, onMinimum: (String) -> Unit) {
    val c = row.candidate
    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "Owes ${c.owed.asMoney(c.currencyCode)}",
                style = MaterialTheme.typography.bodySmall,
                color = ObsidianTokens.debit,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = row.aprInput,
                    onValueChange = onApr,
                    label = { Text("Yearly rate %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.minimumInput,
                    onValueChange = onMinimum,
                    label = { Text("Monthly minimum") },
                    supportingText = if (c.minimumIsEstimate) {
                        { Text("Estimated") }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlanCard(title: String, plan: DebtPayoffSimulator.Plan, money: (BigDecimal) -> String, best: Boolean) {
    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title + if (best) "  ★" else "", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val headline = when (plan.outcome) {
                DebtPayoffSimulator.Outcome.PAID_OFF ->
                    "Debt-free in ${DebtCandidates.durationLabel(plan.months)} · ${money(plan.totalInterest)} interest"
                DebtPayoffSimulator.Outcome.BUDGET_BELOW_MINIMUMS ->
                    "The budget doesn't cover the minimum payments."
                DebtPayoffSimulator.Outcome.NEVER_PAYS_OFF ->
                    "The budget doesn't cover the interest, so the debt would keep growing."
                DebtPayoffSimulator.Outcome.HIT_CAP ->
                    "Not paid off within ${DebtCandidates.durationLabel(plan.months)}. Try a bigger budget."
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium)
            if (plan.outcome == DebtPayoffSimulator.Outcome.PAID_OFF) {
                Spacer(Modifier.height(6.dp))
                plan.debts.forEachIndexed { i, d ->
                    Text(
                        "${i + 1}. ${d.name}: paid off in month ${d.payoffMonth} (${money(d.interestPaid)} interest)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ObsidianTokens.textMeta,
                    )
                }
            }
        }
    }
}
