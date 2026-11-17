package com.xemmy.mmbank.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.intelligence.WhatChangedDigest
import com.xemmy.mmbank.domain.intelligence.WhatChangedWording
import com.xemmy.mmbank.domain.intelligence.CaptureHealthEvaluator
import com.xemmy.mmbank.util.CaptureAccess
import com.xemmy.mmbank.domain.intelligence.CashFlowForecaster
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.onboarding.OnboardingChecklist
import com.xemmy.mmbank.domain.model.FinancialHealthMetrics
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.screens.accounts.UnassignedTotal
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.domain.layout.HomeSections
import com.xemmy.mmbank.ui.components.AccountCarousel
import com.xemmy.mmbank.ui.components.CustomizeLayoutSheet
import com.xemmy.mmbank.ui.components.ManageAccountsBottomSheet
import com.xemmy.mmbank.ui.designsystem.Banner
import com.xemmy.mmbank.ui.designsystem.MMAssistChip
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMTone
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.designsystem.SectionHeader
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.designsystem.moneyLabelWhole
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asQuantity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home: net worth, account balances, crypto and recent activity. Stats, the
 * savings goal and charts live on the Dashboard tab (InsightsScreen).
 */
@Composable
fun DashboardScreen(
    onOpenReview: () -> Unit = {},
    onOpenUnassigned: () -> Unit = {},
    /** Capture & permissions, where the capture-health banner's "Fix" leads. */
    onOpenCaptureSettings: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    /** The month card at the top opens the Cash flow page. */
    onOpenCashFlow: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val captureHealth by viewModel.captureHealth.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Permissions can change in system settings while we're away.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshCaptureAccess(
            notificationAccess = CaptureAccess.notificationAccess(context),
            sms = CaptureAccess.sms(context),
            batteryExempt = CaptureAccess.batteryExempt(context),
        )
        onPauseOrDispose { }
    }
    val weeklyDigest by viewModel.weeklyDigest.collectAsStateWithLifecycle()
    val financialHealth by viewModel.financialHealth.collectAsStateWithLifecycle()
    val dayTotals by viewModel.dayTotals.collectAsStateWithLifecycle()
    val pendingReviewCount by viewModel.pendingReviewCount.collectAsStateWithLifecycle()
    val cardsDue by viewModel.cardsDue.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hidden = state.hideBalances
    var showManageAccounts by remember { mutableStateOf(false) }
    var showCustomize by remember { mutableStateOf(false) }
    val layout by viewModel.homeLayout.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // Day grouping and "today" are computed here: composable calls are not allowed inside the lazy list.
    val today = com.xemmy.mmbank.ui.util.rememberCurrentDate()
    val recentByDay = remember(state.recent) {
        val zone = java.time.ZoneId.systemDefault()
        state.recent.groupBy { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
    }

    Column(Modifier.fillMaxSize()) {
        DashboardHeader()
        // This single LazyColumn hosts several separate items() blocks (accounts,
        // crypto, recent transactions). Compose requires keys to be unique
        // across the WHOLE list, not just within one items() call — Account
        // and Transaction are different Room tables with independent
        // auto-increment ids, so an unprefixed `it.id` collides the moment an
        // account and a transaction share the same numeric id. Every key below
        // must stay prefixed per section.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(MMSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(MMSpacing.cardGap),
        ) {
            captureHealth?.takeIf { it.showBanner }?.let { health ->
                item(key = "capture_health") {
                    CaptureHealthBanner(
                        health = health,
                        onFix = onOpenCaptureSettings,
                        onSnooze = viewModel::snoozeCaptureHealth,
                    )
                }
            }

            onboarding?.let { checklist ->
                item(key = "onboarding") {
                    OnboardingCard(
                        checklist = checklist,
                        onAct = { step ->
                            when (step) {
                                OnboardingChecklist.StepId.ALLOW_CAPTURE -> onOpenCaptureSettings()
                                OnboardingChecklist.StepId.ADD_ACCOUNT -> onOpenAccounts()
                                OnboardingChecklist.StepId.BACK_UP -> onOpenBackup()
                                OnboardingChecklist.StepId.FIRST_TRANSACTION -> Unit
                            }
                        },
                        onDismiss = viewModel::dismissOnboarding,
                    )
                }
            }

            if (pendingReviewCount > 0) {
                item(key = "review_badge") {
                    ReviewBadgeCard(count = pendingReviewCount, onClick = onOpenReview)
                }
            }

            layout.visibleOrder.forEach { sectionId ->
                when (sectionId) {
                    HomeSections.WEEKLY_DIGEST -> {
                        weeklyDigest?.let { digest ->
                            item(key = "weekly_digest") {
                                WeeklyDigestCard(digest = digest, currency = state.baseCurrency, hidden = hidden)
                            }
                        }
                    }
                    HomeSections.NET_WORTH -> {
                        item(key = "net_worth") {
                            NetWorthCard(
                                netWorth = state.netWorthInBase,
                                currency = state.baseCurrency,
                                approximate = state.hasUnconvertedAmounts,
                                cryptoExcluded = state.cryptoExcludedFromTotal,
                                creditCardExcluded = state.creditCardExcludedFromTotal,
                                refreshing = state.refreshing,
                                hidden = hidden,
                                onToggleHide = viewModel::toggleHideBalances,
                                onRefresh = viewModel::refreshRemote,
                            )
                        }
                    }
                    HomeSections.FINANCIAL_HEALTH -> {
                        financialHealth.metrics?.let { metrics ->
                            item(key = "financial_health") {
                                FinancialHealthCard(metrics, financialHealth.cashFlow, state.baseCurrency, hidden)
                            }
                        }
                    }
                    HomeSections.MONTH_CARD -> {
                        item(key = "month_card") {
                            MonthCard(state, hidden, onOpenCashFlow)
                        }
                    }
                    HomeSections.ACCOUNTS -> {
                        item(key = "account_carousel") {
                            AccountCarousel(
                                accounts = state.accounts,
                                selectedAccountId = state.selectedAccountId,
                                hidden = hidden,
                                allAccountsTotal = state.fiatTotalInBase,
                                baseCurrency = state.baseCurrency,
                                onSelectAccount = viewModel::selectAccount,
                                onManageAccounts = { showManageAccounts = true },
                            )
                        }
                    }
                    HomeSections.CARDS_DUE -> {
                        if (cardsDue.isNotEmpty()) {
                            item(key = "cards_due") { CardsDueCard(cardsDue, hidden) }
                        }
                    }
                    HomeSections.UNASSIGNED -> {
                        // Money in no account is in no balance above -- say so rather than
                        // letting it silently vanish from the net worth figure.
                        if (!state.unassigned.isEmpty) {
                            item(key = "unassigned_total") {
                                UnassignedRow(
                                    unassigned = state.unassigned,
                                    hidden = hidden,
                                    onClick = onOpenUnassigned,
                                )
                            }
                        }
                    }
                    HomeSections.CRYPTO -> {
                        if (state.cryptoBalances.isNotEmpty() || state.cryptoWallets.isNotEmpty()) {
                            item { SectionHeader("Crypto (Binance)") }
                            item(key = "crypto_group") {
                                CryptoCard(state)
                            }
                        }
                    }
                    HomeSections.RECENT -> {
                        state.selectedAccountId?.let { accountId ->
                            val accountName = state.accounts.firstOrNull { it.account.id == accountId }?.account?.name
                            item(key = "account_filter_chip") {
                                AccountFilterChip(
                                    label = accountName ?: "Account",
                                    onClear = { viewModel.selectAccount(null) },
                                )
                            }
                        }

                        item { SectionHeader("Recent activity") }
                        if (state.recent.isEmpty()) {
                            item {
                                Text(
                                    "No transactions captured yet. Enable notification access and SMS " +
                                        "permission in Settings, then wait for a bank message.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MMTheme.colors.textSecondary,
                                )
                            }
                        } else {
                            recentByDay.forEach { (day, rows) ->
                                item(key = "recent_day_$day") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            com.xemmy.mmbank.ui.util.dayPillLabel(day, today),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MMTheme.colors.textSecondary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        dayTotals[day]?.takeIf { it.netSign != 0 }?.let { total ->
                                            Text(
                                                com.xemmy.mmbank.ui.util.dayTotalLabel(total, state.baseCurrency, hidden),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MMTheme.colors.textPrimary,
                                            )
                                        }
                                    }
                                }
                                items(rows, key = { "recent_${it.id}" }) { tx ->
                                    // User-defined label wins, then the built-in bank map — so
                                    // "com.adib.mobile" shows as "ADIB", not the raw package.
                                    val bankName = tx.sender?.let { state.senderLabels[it] ?: BankNames.displayName(it) }
                                    RecentTransactionRow(tx, bankName, hidden)
                                    HorizontalDivider(color = MMTheme.colors.borderSubtle)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "customize_layout") {
                TextButton(
                    onClick = { showCustomize = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Customize Home") }
            }
        }
        SnackbarHost(snackbar)
    }

    if (showCustomize) {
        CustomizeLayoutSheet(
            title = "Customize Home",
            layout = layout,
            specs = HomeSections.specs,
            onMove = viewModel::moveHomeSection,
            onToggle = viewModel::toggleHomeSection,
            onReset = viewModel::resetHomeLayout,
            onDismiss = { showCustomize = false },
        )
    }

    if (showManageAccounts) {
        ManageAccountsBottomSheet(
            accounts = state.manageableAccounts,
            hidden = hidden,
            onRename = viewModel::renameAccount,
            onToggleVisibility = viewModel::toggleAccountVisibility,
            onToggleNetWorth = viewModel::toggleAccountInNetWorth,
            onMove = viewModel::moveAccount,
            onDismiss = { showManageAccounts = false },
        )
    }
}

/** Card payments due within a week or overdue, most urgent first. */
@Composable
private fun CardsDueCard(cards: List<CardPaymentReminders.CardReminder>, hidden: Boolean) {
    // The LazyColumn's contentPadding already supplies the gutter; no extra inset here.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = MMSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(MMSpacing.xs),
    ) {
        Text("Cards due", style = MaterialTheme.typography.titleSmall)
        cards.forEach { card ->
            val days = card.status.daysUntilDue ?: return@forEach
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${card.accountName} · ${CardPaymentReminders.whenText(days)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (days < 0) MMTheme.colors.danger else MMTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    moneyLabel(card.status.amountDue, card.currencyCode, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Dashboard header: screen title. Ambient background style now lives in Settings > Appearance. */
@Composable
private fun DashboardHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MMSpacing.gutter, vertical = MMSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Home", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

/** Compact "filtered by [label]" chip above the feed, with an X to clear the carousel's account selection. */
@Composable
private fun AccountFilterChip(label: String, onClear: () -> Unit) {
    MMAssistChip(
        label = "Filtered: $label",
        onClick = onClear,
        leadingIcon = {
            Icon(Icons.Filled.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
        },
    )
}

/**
 * "Capture may have stopped" -- the app's main way of failing is silently,
 * so this sits above everything else on Home (see [CaptureHealthEvaluator]).
 */
@Composable
private fun CaptureHealthBanner(
    health: CaptureHealthEvaluator.Health,
    onFix: () -> Unit,
    onSnooze: () -> Unit,
) {
    Banner(
        tone = MMTone.DANGER,
        title = when (health.state) {
            CaptureHealthEvaluator.State.NO_ACCESS -> "Capture is off"
            CaptureHealthEvaluator.State.DISCONNECTED -> "Capture may have stopped"
            else -> "Unusually quiet"
        },
        body = health.message,
        modifier = Modifier.fillMaxWidth(),
        actions = {
            if (health.state != CaptureHealthEvaluator.State.NO_ACCESS) {
                TextButton(onClick = onSnooze) { Text("Snooze 24h") }
            }
            TextButton(onClick = onFix) { Text("Fix") }
        },
    )
}

/**
 * "Finish setting up": the four first-run steps, ticked off from real state,
 * with a button for the next one. Dismissable for good; disappears on its own
 * when everything is done (see [OnboardingChecklist]).
 */
@Composable
private fun OnboardingCard(
    checklist: OnboardingChecklist.Checklist,
    onAct: (OnboardingChecklist.StepId) -> Unit,
    onDismiss: () -> Unit,
) {
    MMCard(Modifier.fillMaxWidth(), tone = MMTone.INFO) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Finish setting up · ${checklist.doneCount} of ${checklist.steps.size}",
                style = MaterialTheme.typography.titleSmall,
            )
            checklist.steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (step.done) "✓" else "○", style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        if (!step.done && step.id == checklist.next?.id) {
                            Text(
                                step.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                checklist.next?.takeIf { it.id != OnboardingChecklist.StepId.FIRST_TRANSACTION }?.let { next ->
                    TextButton(onClick = { onAct(next.id) }) { Text("Do this") }
                }
            }
        }
    }
}

/** "N items to review" entry point into the High-Detail Review Transactions screen; hidden by the caller when count is 0. */
@Composable
private fun ReviewBadgeCard(count: Int, onClick: () -> Unit) {
    MMCard(Modifier.fillMaxWidth(), onClick = onClick, tone = MMTone.INFO) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MMTheme.colors.info,
            )
            Text(
                "$count ${if (count == 1) "item" else "items"} to review",
                style = MaterialTheme.typography.titleSmall,
                color = MMTheme.colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MMSpacing.md),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MMTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * "N transactions aren't in any account" -- money that is in the ledger but in
 * no balance, so it is missing from the net worth above with nothing to
 * explain the gap. Tapping opens Activity already filtered to those rows.
 *
 * Shaped like [ReviewBadgeCard]: both are "here is a pile of things needing
 * your attention, tap to go fix them".
 */
@Composable
private fun UnassignedRow(
    unassigned: UnassignedTotal,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    MMCard(Modifier.fillMaxWidth(), onClick = onClick, tone = MMTone.WARNING) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = MMTheme.colors.textSecondary,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = MMSpacing.md),
            ) {
                Text(
                    if (unassigned.entryCount == 1) {
                        "1 transaction isn't in any account"
                    } else {
                        "${unassigned.entryCount} transactions aren't in any account"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MMTheme.colors.textPrimary,
                )
                Text(
                    unassignedTotalsLabel(unassigned.totalsByCurrency, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MMTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun NetWorthCard(
    netWorth: Double,
    currency: String,
    approximate: Boolean,
    cryptoExcluded: Boolean,
    creditCardExcluded: Boolean,
    refreshing: Boolean,
    hidden: Boolean,
    onToggleHide: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = MMTheme.colors
    MMCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Net worth", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                Spacer(Modifier.height(MMSpacing.xs))
                AmountText(
                    display = netWorthDisplay(netWorth, currency, approximate, hidden),
                    color = if (!hidden && netWorth < 0) colors.moneyOut else colors.accent,
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (approximate && !hidden) {
                    Text(
                        "Some balances lack an exchange rate — refresh rates in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (cryptoExcluded && !hidden) {
                    Text(
                        "Crypto excluded from total — change in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (creditCardExcluded && !hidden) {
                    Text(
                        "Credit card excluded from total — change in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            IconButton(onClick = onToggleHide) {
                Icon(
                    if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (hidden) "Show balances" else "Hide balances",
                    tint = colors.textSecondary,
                )
            }
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh rates and balances",
                        tint = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * "AI Weekly Digest" hero card at the top of the Dashboard: a plain-English
 * read of this week's spend vs last week's plus the single biggest expense —
 * e.g. "Spent 12% less this week ($320 vs $365). Top spend: $85 at Target."
 */
@Composable
private fun WeeklyDigestCard(
    digest: WhatChangedDigest.Digest,
    currency: String,
    hidden: Boolean,
) {
    val wording = remember(currency, hidden) {
        WhatChangedWording(
            money = { moneyLabel(it, currency, hidden) },
            moneyIn = { amount, code -> moneyLabel(amount, code, hidden) },
        )
    }
    MMCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MMTheme.colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("This week", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                Spacer(Modifier.height(MMSpacing.xs))
                Text(wording.headline(digest), style = MaterialTheme.typography.bodyMedium)
                digest.items.forEach { item ->
                    Spacer(Modifier.height(MMSpacing.xs))
                    Text(
                        "• " + wording.item(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MMTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Financial Health Summary Card: this month's savings rate, cash runway and
 * net cash flow (see [FinancialHealthEngine]), plus a "safe to spend until
 * payday" cash-flow forecast (see [CashFlowForecaster]) when a payday can be
 * determined.
 */
@Composable
private fun FinancialHealthCard(
    metrics: FinancialHealthMetrics,
    cashFlow: CashFlowForecaster.SafeToSpendResult?,
    currency: String,
    hidden: Boolean,
) {
    val colors = MMTheme.colors
    MMCard(Modifier.fillMaxWidth()) {
        Text("Financial health", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HealthMetricTile(
                label = "Savings rate",
                value = "${metrics.savingsRatePercent.roundToInt()}%",
                valueColor = if (metrics.savingsRatePercent >= 0) colors.moneyIn else colors.moneyOut,
            )
            HealthMetricTile(
                label = "Cash runway",
                value = metrics.cashRunwayMonths?.let { "${it.roundToInt()} mo" } ?: "—",
                valueColor = colors.textPrimary,
            )
            HealthMetricTile(
                label = "Net cash flow",
                value = moneyLabelWhole(metrics.netCashFlow, currency, hidden),
                valueColor = if (metrics.netCashFlow >= 0) colors.moneyIn else colors.moneyOut,
            )
        }
        if (cashFlow != null) {
            Spacer(Modifier.height(MMSpacing.md))
            HorizontalDivider(color = colors.borderSubtle)
            Spacer(Modifier.height(10.dp))
            val paydayLabel = Instant.ofEpochMilli(cashFlow.nextPaydayMillis).atZone(ZoneId.systemDefault())
                .toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
            Text(
                "Safe to spend until payday ($paydayLabel, ${cashFlow.daysUntilPayday}d): " +
                    "${moneyLabel(cashFlow.safeToSpend.toDouble(), currency, hidden)} " +
                    "(${moneyLabel(cashFlow.dailyAllowance.toDouble(), currency, hidden)}/day)",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun HealthMetricTile(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)
        Spacer(Modifier.height(MMSpacing.xxs))
        AmountText(display = value, color = valueColor, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Collapsible Binance overview. Collapsed it is a single total line; expanded
 * it lists every wallet with a balance (Earn, Funding, Trading Bots, …) and
 * the spot per-asset detail — both pre-sorted by value in the view model.
 */
@Composable
private fun CryptoCard(state: DashboardUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hidden = state.hideBalances
    val colors = MMTheme.colors
    val summary = buildList {
        if (state.cryptoWallets.isNotEmpty()) add("${state.cryptoWallets.size} wallets")
        if (state.cryptoBalances.isNotEmpty()) add("${state.cryptoBalances.size} spot assets")
    }.joinToString(" · ")

    MMCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Total across wallets", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Text(
                moneyLabel(state.cryptoTotalUsd, "USD", hidden),
                style = MaterialTheme.typography.titleSmall,
                color = colors.accent,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textSecondary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                state.cryptoWallets.forEach { wallet ->
                    HorizontalDivider(Modifier.padding(top = MMSpacing.md), color = colors.borderSubtle)
                    Row(
                        Modifier.padding(top = MMSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(wallet.walletName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (hidden) AMOUNT_MASK else "≈ ${wallet.balanceBtc.asQuantity()} BTC",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                        Text(
                            usdValueLabel(wallet.valueUsd, hidden),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }

                if (state.cryptoBalances.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = MMSpacing.md), color = colors.borderSubtle)
                    Text(
                        "HOLDINGS (SPOT · FUNDING · EARN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier.padding(top = MMSpacing.md),
                    )
                    state.cryptoBalances.forEach { asset ->
                        Row(
                            Modifier.padding(top = MMSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(asset.asset, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (hidden) AMOUNT_MASK else asset.total.asQuantity(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                )
                            }
                            Text(
                                usdValueLabel(asset.valueUsd, hidden),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentTransactionRow(tx: Transaction, bankName: String? = null, hidden: Boolean = false) {
    val title = tx.merchant ?: bankName ?: tx.sender ?: tx.source.name
    val subtitle = listOfNotNull(
        tx.person,
        bankName?.takeIf { it != title },
        tx.source.name.lowercase().replace('_', ' '),
        tx.timestampMillis.asDateTime(),
    ).joinToString(" · ")
    // Same audit-friendly muting as the Activity feed: excluded rows stay
    // listed but render dimmed with a slashed-eye marker.
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (tx.excludeFromStats) 0.55f else 1f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
        }
        MoneyText(
            amount = tx.amount.toDouble(),
            currencyCode = tx.currencyCode,
            kind = homeRowKind(tx.direction),
            style = MaterialTheme.typography.bodyLarge,
            hidden = hidden,
        )
        if (tx.excludeFromStats) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Excluded from analytics",
                modifier = Modifier.padding(start = MMSpacing.xs).size(14.dp),
                tint = MMTheme.colors.textSecondary,
            )
        }
    }
}


/** This month at a glance: net, money in and out, and the last six months' net as a line. Tap for Cash flow. */
@Composable
private fun MonthCard(state: DashboardUiState, hidden: Boolean, onOpen: () -> Unit) {
    val monthly = state.stats.monthly
    val current = monthly.lastOrNull() ?: return
    if (monthly.all { it.income == 0.0 && it.spend == 0.0 }) return
    val net = current.income - current.spend
    val currency = state.baseCurrency
    MMCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(MMSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This month", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                Text("Cash flow ›", style = MaterialTheme.typography.labelMedium, color = MMTheme.colors.textSecondary)
            }
            Text(
                (if (net < 0) "\u2212" else if (net > 0) "+" else "") + moneyLabel(kotlin.math.abs(net), currency, hidden),
                style = MaterialTheme.typography.headlineSmall,
                color = MMTheme.colors.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("In ${moneyLabel(current.income, currency, hidden)}", style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.moneyIn)
                Text("Out ${moneyLabel(current.spend, currency, hidden)}", style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.moneyOut)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.xemmy.mmbank.ui.designsystem.LocalHideBalances provides hidden,
            ) {
                com.xemmy.mmbank.ui.designsystem.Sparkline(
                    values = monthly.map { it.income - it.spend },
                    title = "Net per month, last six months",
                    valueLabel = { moneyLabel(it, currency, hidden) },
                )
            }
        }
    }
}
