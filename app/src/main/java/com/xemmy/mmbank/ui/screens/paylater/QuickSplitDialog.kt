package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.paylater.PayLaterSchedule
import com.xemmy.mmbank.domain.paylater.PayLaterSplit
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Saves the plan the quick split dialog builds. */
class QuickSplitViewModel(private val container: AppContainer) : ViewModel() {
    /** Existing plans, for the "Paid from" default of a charge that names no card. */
    val plans: StateFlow<List<PayLaterPlan>> = container.payLaterRepository.observePlans()
        .catch { e ->
            container.recordError("QuickSplitViewModel.plans", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(split: PayLaterSplit.Split, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.payLaterRepository.createPlan(split.plan, split.installments) }
                .onFailure { container.recordError("QuickSplitViewModel.create", it) }
            onDone()
        }
    }
}

private val PRESET_COUNTS = listOf(2, 3, 4, 6, 12)
private val DUE_FORMAT = DateTimeFormatter.ofPattern("d MMM")

/**
 * "Split with Tabby ×X" without the full editor: pick X, say whether this charge is one payment or
 * the full price, see the result, Create. "More options" opens the full plan editor.
 */
@Composable
fun QuickSplitDialog(
    charge: Transaction,
    onDismiss: () -> Unit,
    onMoreOptions: () -> Unit,
    viewModel: QuickSplitViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val looksLikeTabby = charge.matchedRuleName?.contains("tabby", ignoreCase = true) == true
    var provider by remember { mutableStateOf("Tabby") }
    var count by remember { mutableStateOf(4) }
    var otherText by remember { mutableStateOf("") }
    var otherSelected by remember { mutableStateOf(false) }
    var oneInstallment by remember { mutableStateOf(looksLikeTabby) }
    var cadence by remember { mutableStateOf(if (looksLikeTabby) PayLaterCadence.BIWEEKLY else PayLaterCadence.MONTHLY) }
    val zone = ZoneId.systemDefault()
    val plans by viewModel.plans.collectAsState()

    val valid = count in PayLaterSchedule.MIN_COUNT..PayLaterSchedule.MAX_COUNT
    val split = if (valid) PayLaterSplit.fromCharge(charge, count, oneInstallment, cadence, zone, provider, plans = plans) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split with $provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = provider, onValueChange = { provider = it }, label = { Text("Provider") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Payments (X)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESET_COUNTS.forEach { n ->
                        FilterChip(
                            selected = !otherSelected && count == n,
                            onClick = { otherSelected = false; count = n },
                            label = { Text("$n") },
                        )
                    }
                    FilterChip(selected = otherSelected, onClick = { otherSelected = true }, label = { Text("Other") })
                }
                if (otherSelected) {
                    OutlinedTextField(
                        value = otherText,
                        onValueChange = { text ->
                            otherText = text.filter { it.isDigit() }.take(2)
                            count = otherText.toIntOrNull() ?: 0
                        },
                        label = { Text("Number of payments") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("This charge is…", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = oneInstallment, onClick = { oneInstallment = true }, label = { Text("One payment of X") })
                    FilterChip(selected = !oneInstallment, onClick = { oneInstallment = false }, label = { Text("The full price") })
                }
                Text("Every", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cadence == PayLaterCadence.WEEKLY, onClick = { cadence = PayLaterCadence.WEEKLY }, label = { Text("Week") })
                    FilterChip(selected = cadence == PayLaterCadence.BIWEEKLY, onClick = { cadence = PayLaterCadence.BIWEEKLY }, label = { Text("2 weeks") })
                    FilterChip(selected = cadence == PayLaterCadence.MONTHLY, onClick = { cadence = PayLaterCadence.MONTHLY }, label = { Text("Month") })
                }
                if (split != null) {
                    val each = split.installments.getOrNull(1)?.amount ?: split.installments.first().amount
                    val next = split.installments.firstOrNull { !it.isPaid }
                    Text(
                        "Total ${moneyLabel(split.plan.totalAmount, split.plan.currencyCode)} · " +
                            "$count × ${moneyLabel(each, split.plan.currencyCode)}" +
                            (next?.let { " · next due " + Instant.ofEpochMilli(it.dueDateMillis).atZone(zone).toLocalDate().format(DUE_FORMAT) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        if (PayLaterSplit.laterPaymentsCountAsSpending(oneInstallment)) {
                            "Each later payment counts as spending."
                        } else {
                            "Already counted in the original purchase."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = split != null, onClick = { split?.let { viewModel.create(it, onDismiss) } }) { Text("Create") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMoreOptions) { Text("More options") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
