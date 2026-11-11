package com.xemmy.mmbank.ui.screens.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.intelligence.FinancialHealthEngine
import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.report.MonthlyReport
import com.xemmy.mmbank.domain.report.MonthlyReportBuilder
import com.xemmy.mmbank.domain.report.MonthlyReportHtmlFormatter
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.ObsidianCard
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MonthlyReportUiState(
    val month: YearMonth = YearMonth.now(),
    val report: MonthlyReport? = null,
)

class MonthlyReportViewModel(private val container: AppContainer) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val status = MutableStateFlow<String?>(null)

    /**
     * Export feedback, exposed on its own rather than folded into [uiState]:
     * the ledger `combine` below is already at Kotlin's 5-argument limit, and a
     * transient status message has no business re-running report assembly.
     */
    val statusMessage: StateFlow<String?> = status.asStateFlow()

    /** The ledger inputs a report is built from, bundled so the outer combine stays within its arity limit. */
    private data class ReportInputs(
        val transactions: List<Transaction>,
        val accounts: List<Account>,
        val categoryNames: Map<Long, String>,
        val subscriptions: List<Subscription>,
        val goals: List<SavingsGoal>,
    )

    private val inputs = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.categoryRepository.observeAll(),
        container.subscriptionRepository.observeActive(),
        container.savingsGoalRepository.observeActive(),
    ) { transactions, accounts, categories, subscriptions, goals ->
        ReportInputs(transactions, accounts, categories.associate { it.id to it.name }, subscriptions, goals)
    }

    val uiState: StateFlow<MonthlyReportUiState> = combine(inputs, month) { bundle, selectedMonth ->
        val transactions = bundle.transactions
        val accounts = bundle.accounts
        val subscriptions = bundle.subscriptions
        val currency = container.preferences.defaultCurrency
        val zone = ZoneId.systemDefault()

        fun window(target: YearMonth): Pair<Long, Long> {
            val start = target.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = target.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return start to end
        }

        fun slice(target: YearMonth): List<Transaction> {
            val (start, end) = window(target)
            return transactions.filter { it.timestampMillis in start until end }
        }

        val monthTransactions = slice(selectedMonth)
        // The same formula the Accounts and Home screens use, so the report's
        // net worth agrees with them. This deliberately does NOT filter
        // excludeFromStats: that flag keeps balance corrections and transfer
        // legs out of income/spend analytics (which `income`/`expenses` below
        // still honour), but they are real movements and dropping them here is
        // what used to make this figure disagree with the Accounts screen.
        val netByAccount = AccountBalanceCalculator.netByAccount(
            transactions.map {
                AccountBalanceCalculator.LedgerEntry(
                    accountId = it.accountId,
                    amount = it.amount,
                    currencyCode = it.currencyCode,
                    direction = it.direction,
                )
            }
        )

        val balances = accounts.map { account ->
            NetWorthAggregator.AccountBalance(
                accountId = account.id,
                type = account.type,
                balance = AccountBalanceCalculator.balanceOf(account, netByAccount),
                currencyCode = account.currencyCode,
            )
        }

        val liquid = balances
            .filter { it.type in LIQUID_TYPES }
            .fold(BigDecimal.ZERO) { acc, it -> acc + it.balance }

        // Same scope as the report headline: one currency, LedgerMath's
        // definition of spent/received. Summing every currency at face value
        // here used to make the health figures disagree with the headline.
        val inCurrency = monthTransactions.filter { it.currencyCode.equals(currency, ignoreCase = true) }
        val income = LedgerMath.incomeExact(inCurrency)
        val expenses = LedgerMath.netSpendExact(inCurrency)

        val report = MonthlyReportBuilder.build(
            monthLabel = selectedMonth.format(MONTH_FORMAT),
            currency = currency,
            monthTransactions = monthTransactions,
            previousMonthTransactions = slice(selectedMonth.minusMonths(1)),
            categoryNames = bundle.categoryNames,
            accountBalances = balances,
            subscriptions = subscriptions,
            goals = bundle.goals,
            health = FinancialHealthEngine.calculate(
                FinancialHealthEngine.HealthInputs(
                    periodIncome = income.toDouble(),
                    periodExpenses = expenses.toDouble(),
                    liquidBalance = liquid.toDouble(),
                    averageMonthlyFixedExpenses = container.preferences.fixedMonthlyExpenses,
                )
            ),
        )
        MonthlyReportUiState(month = selectedMonth, report = report)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthlyReportUiState())

    fun showPreviousMonth() {
        month.value = month.value.minusMonths(1)
    }

    /** Never advances past the current month — there is nothing to report on yet. */
    fun showNextMonth() {
        if (month.value < YearMonth.now()) month.value = month.value.plusMonths(1)
    }

    /** Suggested filename for the export dialog. */
    fun fileName(): String = "mmbank-${month.value}.html"

    /** [writeText] runs on IO and streams the rendered HTML to the chosen document. */
    fun export(writeText: suspend (String) -> Unit) {
        val report = uiState.value.report ?: return
        viewModelScope.launch {
            runCatching {
                val html = MonthlyReportHtmlFormatter.format(report)
                withContext(Dispatchers.IO) { writeText(html) }
            }.onSuccess {
                status.value = "Report saved"
            }.onFailure {
                container.recordError("MonthlyReportViewModel.export", it)
                status.value = "Export failed: ${it.message}"
            }
        }
    }

    private companion object {
        /** Cash-equivalent accounts, for the health engine's runway figure. */
        val LIQUID_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    }
}

/**
 * A month's statement: headline figures with month-over-month deltas, the
 * asset/liability split, where the money went, subscriptions and health
 * metrics — exportable as a self-contained HTML file that opens in any browser
 * (and prints to PDF from there).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportScreen(
    onBack: () -> Unit,
    viewModel: MonthlyReportViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            viewModel.export { html ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                    ?: error("Couldn't open the selected file")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        LazyColumn(
            modifier = Modifier.fillMaxSize().obsidianCanvas().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "month_picker") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::showPreviousMonth) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(
                        report?.monthLabel ?: state.month.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ObsidianTokens.textPrimary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = viewModel::showNextMonth,
                        enabled = state.month < YearMonth.now(),
                    ) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                    }
                }
            }

            if (report == null || report.transactionCount == 0) {
                item(key = "empty") {
                    Text(
                        "No transactions captured in this month.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item(key = "summary") { SummaryCard(report) }
                report.netWorth?.let { netWorth ->
                    item(key = "net_worth") {
                        SectionCard("Net worth") {
                            AmountRow("Assets", netWorth.debitTotal, report.currency)
                            AmountRow("Liabilities", netWorth.creditTotal, report.currency)
                            AmountRow("Net worth", netWorth.netWorth, report.currency, strong = true)
                        }
                    }
                }
                if (report.topCategories.isNotEmpty()) {
                    item(key = "categories") {
                        SectionCard("Where it went") {
                            report.topCategories.forEach { line ->
                                AmountRow(
                                    "${line.name}  ${(line.shareOfSpend * 100).toInt()}%",
                                    line.amount,
                                    report.currency,
                                )
                            }
                        }
                    }
                }
                if (report.topMerchants.isNotEmpty()) {
                    item(key = "merchants") {
                        SectionCard("Top merchants") {
                            report.topMerchants.forEach { line ->
                                AmountRow("${line.name}  ×${line.transactionCount}", line.amount, report.currency)
                            }
                        }
                    }
                }
                if (report.subscriptions.isNotEmpty()) {
                    item(key = "subscriptions") {
                        SectionCard("Subscriptions") {
                            report.subscriptions.forEach { subscription ->
                                AmountRow(
                                    subscription.merchant,
                                    subscription.averageAmount,
                                    subscription.currencyCode,
                                )
                            }
                            AmountRow(
                                "Monthly equivalent",
                                report.subscriptionMonthlyTotal,
                                report.currency,
                                strong = true,
                            )
                        }
                    }
                }

                item(key = "export") {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch(viewModel.fileName()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export as HTML")
                    }
                }
            }

            statusMessage?.let { message ->
                item(key = "status") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(report: MonthlyReport) {
    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountRow("Income", report.income, report.currency, delta = report.incomeDelta, higherIsBetter = true)
            AmountRow("Spent", report.spent, report.currency, delta = report.spentDelta, higherIsBetter = false)
            AmountRow("Net", report.net, report.currency, strong = true)
            MetaLabel("${report.transactionCount} transactions")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaLabel(title)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun AmountRow(
    label: String,
    amount: BigDecimal,
    currency: String,
    strong: Boolean = false,
    delta: BigDecimal? = null,
    higherIsBetter: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                color = ObsidianTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A delta only means something with a direction: more income is
            // good, more spend is not.
            delta?.takeIf { it.signum() != 0 }?.let { change ->
                val up = change.signum() > 0
                val good = up == higherIsBetter
                Text(
                    (if (up) "▲ " else "▼ ") + change.abs().asMoney(currency) + " vs last month",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (good) ObsidianTokens.credit else ObsidianTokens.debit,
                )
            }
        }
        Text(
            amount.asMoney(currency),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            color = ObsidianTokens.textPrimary,
        )
    }
}
