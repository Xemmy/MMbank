package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.paylater.PayLaterSchedule
import com.xemmy.mmbank.domain.paylater.TabbySplitDetector
import com.xemmy.mmbank.domain.paylater.TabbySplitPlan
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The "Tabby split found -> Review" prompt on Activity. Finds Tabby checkouts with no plan yet
 * ([TabbySplitDetector], today's and earlier ones) and, once the user confirms, saves the plan and
 * links / tidies the rows ([TabbySplitPlan]). Nothing happens without that confirmation.
 */
class TabbySplitReviewViewModel(private val container: AppContainer) : ViewModel() {

    private val dismissed = MutableStateFlow(container.preferences.dismissedPayLaterSplitIds)

    val candidates: StateFlow<List<TabbySplitDetector.Candidate>> = combine(
        container.transactionRepository.observeTransactions(),
        container.payLaterRepository.observePlans(),
        container.payLaterRepository.observeInstallments(),
        dismissed,
    ) { transactions, plans, installments, dismissedIds ->
        TabbySplitDetector.find(transactions, plans, installments, dismissedIds)
    }
        .flowOn(Dispatchers.Default)
        .recoverAndRetry("TabbySplitReviewViewModel.candidates", container) { emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountNames: StateFlow<Map<Long, String>> = container.accountRepository.observeActive()
        .map { accounts -> accounts.associate { it.id to it.name } }
        .recoverAndRetry("TabbySplitReviewViewModel.accountNames", container) { emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun create(
        candidate: TabbySplitDetector.Candidate,
        count: Int,
        mode: TabbySplitPlan.Mode,
        markMissingPaid: Boolean,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                container.payLaterRepository.createFromSplitReview(
                    TabbySplitPlan.build(candidate, count, mode, markMissingPaid),
                )
            }.onFailure { container.recordError("TabbySplitReviewViewModel.create", it) }
            onDone()
        }
    }

    /** Every split found, each with its detected count (charges are already shared out between them). */
    fun createAll(candidates: List<TabbySplitDetector.Candidate>, mode: TabbySplitPlan.Mode, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            candidates.forEach { candidate ->
                runCatching {
                    container.payLaterRepository.createFromSplitReview(
                        TabbySplitPlan.build(candidate, candidate.count, mode, markMissingPaid = true),
                    )
                }.onFailure { container.recordError("TabbySplitReviewViewModel.createAll", it) }
            }
            onDone()
        }
    }

    /** "Not a split": never offer this purchase again. */
    fun dismiss(candidate: TabbySplitDetector.Candidate) {
        val next = container.preferences.dismissedPayLaterSplitIds + candidate.purchase.id
        container.preferences.dismissedPayLaterSplitIds = next
        dismissed.value = next
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")
private val PRESET_COUNTS = listOf(2, 3, 4, 6, 12)

private fun day(millis: Long, zone: ZoneId, format: DateTimeFormatter = DAY_FORMAT): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(format)

/** Activity's entry point; shown only while a split is waiting for review. */
@Composable
fun TabbySplitBanner(candidates: List<TabbySplitDetector.Candidate>, onClick: () -> Unit) {
    if (candidates.isEmpty()) return
    val single = candidates.singleOrNull()
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                if (single != null) {
                    "${single.provider} split found · ${single.merchant} " +
                        moneyLabel(single.purchase.amount, single.purchase.currencyCode)
                } else {
                    "${candidates.size} Tabby splits found"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text("Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** Picks between "each payment when paid" (the default) and "full price today". */
@Composable
private fun ModeChoice(
    mode: TabbySplitPlan.Mode,
    onMode: (TabbySplitPlan.Mode) -> Unit,
    perPaymentHint: String,
    fullPriceHint: String,
) {
    Text("Count it as", style = MaterialTheme.typography.labelLarge)
    listOf(
        Triple(TabbySplitPlan.Mode.PER_PAYMENT, "Each payment when paid", perPaymentHint),
        Triple(TabbySplitPlan.Mode.FULL_PRICE, "Full price on the purchase day", fullPriceHint),
    ).forEach { (option, title, hint) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onMode(option) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == option, onClick = { onMode(option) })
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Every split found; tap one to review it, or create them all with one choice. */
@Composable
fun TabbySplitListDialog(
    candidates: List<TabbySplitDetector.Candidate>,
    onOpen: (TabbySplitDetector.Candidate) -> Unit,
    onCreateAll: (TabbySplitPlan.Mode) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var mode by remember { mutableStateOf(TabbySplitPlan.Mode.PER_PAYMENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tabby splits found") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Each purchase below was paid with Tabby in installments. Tap one to check it, or create all plans at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                candidates.forEach { candidate ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(candidate) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            "${candidate.merchant} · ${moneyLabel(candidate.purchase.amount, candidate.purchase.currencyCode)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${day(candidate.purchase.timestampMillis, zone, DATE_FORMAT)} · " +
                                "${candidate.match.foundCount} of ${candidate.count} payments found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                ModeChoice(
                    mode = mode,
                    onMode = { mode = it },
                    perPaymentHint = "Each installment counts in the month it was paid; the purchases are hidden from spending.",
                    fullPriceHint = "Each purchase counts once, on its day; the card payments are hidden from spending.",
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreateAll(mode) }) { Text("Create all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One split: the count, the payments found, how to count it; Create plan / Not a split / Later. */
@Composable
fun TabbySplitReviewDialog(
    candidate: TabbySplitDetector.Candidate,
    accountNames: Map<Long, String>,
    onCreate: (count: Int, mode: TabbySplitPlan.Mode, markMissingPaid: Boolean) -> Unit,
    onNotASplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val purchase = candidate.purchase
    val currency = purchase.currencyCode
    var count by remember(candidate.purchase.id) { mutableStateOf(candidate.count) }
    var mode by remember(candidate.purchase.id) { mutableStateOf(TabbySplitPlan.Mode.PER_PAYMENT) }
    var markMissingPaid by remember(candidate.purchase.id) { mutableStateOf(true) }

    val match = remember(candidate, count) { TabbySplitDetector.matchFor(candidate, count, zone) }
    val firstDay = Instant.ofEpochMilli(purchase.timestampMillis).atZone(zone).toLocalDate()
    val schedule = remember(candidate, count) {
        PayLaterSchedule.generate(purchase.amount, count, firstDay, PayLaterCadence.MONTHLY, zone)
    }
    val each = schedule.first().amount
    val missingPast = schedule.indices.count { i -> match.payments.getOrNull(i) == null && TabbySplitPlan.isOverdue(schedule[i], now) }
    val counts = (PRESET_COUNTS + candidate.count).distinct().sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${candidate.provider} split · ${candidate.merchant}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${moneyLabel(purchase.amount, currency)} on ${day(purchase.timestampMillis, zone, DATE_FORMAT)} · " +
                        "every month on day ${firstDay.dayOfMonth}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Payments", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts.forEach { n ->
                        FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") })
                    }
                }
                schedule.forEachIndexed { i, inst ->
                    val payment = match.payments.getOrNull(i)
                    val status = when {
                        payment != null -> "paid " + day(payment.timestampMillis, zone) +
                            (payment.accountId?.let { accountNames[it] }?.let { " · $it" } ?: "")
                        TabbySplitPlan.isOverdue(inst, now) -> "no payment found"
                        else -> "upcoming"
                    }
                    Text(
                        "${i + 1}. ${day(inst.dueDateMillis, zone)} · ${moneyLabel(inst.amount, currency)} · $status",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (missingPast > 0) {
                    Row(Modifier.fillMaxWidth().clickable { markMissingPaid = !markMissingPaid }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = markMissingPaid, onCheckedChange = { markMissingPaid = it })
                        Text("Mark payments not found as paid", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ModeChoice(
                    mode = mode,
                    onMode = { mode = it },
                    perPaymentHint = "${moneyLabel(each, currency)} counts each month it's paid; " +
                        "the ${moneyLabel(purchase.amount, currency)} purchase is hidden from spending.",
                    fullPriceHint = "${moneyLabel(purchase.amount, currency)} counts on ${day(purchase.timestampMillis, zone)}; " +
                        "the monthly card payments are hidden from spending.",
                )
                val notes = buildList {
                    val receipts = match.duplicateReceipts.size
                    if (receipts > 0) {
                        add(
                            if (receipts == 1) "Tabby's \"payment received\" message repeats a card payment and will be removed."
                            else "$receipts Tabby \"payment received\" messages repeat card payments and will be removed.",
                        )
                    }
                    val legacy = match.legacyReceiptPayments.size
                    if (legacy > 0) add("$legacy Tabby payment receipt(s) recorded as income become the payment.")
                    if (mode == TabbySplitPlan.Mode.PER_PAYMENT && match.payments.drop(1).any { it != null }) {
                        add("Spending in past months changes to include these payments.")
                    }
                }
                notes.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(count, mode, markMissingPaid) }) { Text("Create plan") } },
        dismissButton = {
            Row {
                TextButton(onClick = onNotASplit) { Text("Not a split") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}
