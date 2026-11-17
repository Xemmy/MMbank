package com.xemmy.mmbank.ui.screens.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.people.PersonLists
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.AiParsingIndicator
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.designsystem.ListRow
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MAX_LENGTH
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val DATE_LABEL = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US)
private val TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/**
 * One full-screen page to add a transaction (`id` null / -1) or edit a saved one. Replaces the two
 * stacked pop-ups: real date and time pickers, input that survives rotation, and a discard prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorScreen(
    transactionId: Long?,
    onClose: () -> Unit,
    /** Set by "Log payment" on a bill: prefills the form, and saving also advances that bill. */
    billId: Long? = null,
    /** "Split with Pay Later" on a saved purchase: opens the plan form prefilled from it. */
    onSplitWithPayLater: (Long) -> Unit = {},
    viewModel: TransactionsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isEdit = transactionId != null && transactionId > 0
    // For an edit, wait for the saved row; a row deleted meanwhile just closes the page.
    val loaded by produceState<LoadState>(LoadState.Loading, transactionId) {
        value = if (!isEdit) {
            LoadState.Ready(null, emptySet(), billId?.let { viewModel.loadBill(it) })
        } else {
            viewModel.loadTransaction(transactionId!!)?.let { LoadState.Ready(it, viewModel.labelIdsOf(it.id), null) } ?: LoadState.Missing
        }
    }
    when (val l = loaded) {
        LoadState.Loading -> MMScaffold(title = "Transaction", onBack = onClose) { }
        LoadState.Missing -> LaunchedEffect(Unit) { onClose() }
        is LoadState.Ready -> EditorBody(l.transaction, l.labelIds, l.bill, state, viewModel, onClose, onSplitWithPayLater)
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Missing : LoadState
    data class Ready(val transaction: Transaction?, val labelIds: Set<Long>, val bill: com.xemmy.mmbank.domain.model.Bill?) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBody(
    existing: Transaction?,
    existingLabelIds: Set<Long>,
    bill: com.xemmy.mmbank.domain.model.Bill?,
    state: TransactionsUiState,
    viewModel: TransactionsViewModel,
    onClose: () -> Unit,
    onSplitWithPayLater: (Long) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val isEdit = existing != null
    val allLabels by viewModel.labels.collectAsStateWithLifecycle()
    val initial = remember(existing) {
        TransactionEditorForm.initial(
            existing, state.defaultCurrency, zone, LocalDateTime.now(), existingLabelIds,
            prefill = bill?.let(com.xemmy.mmbank.domain.bills.RepeatPlanner::prefillFor),
        )
    }
    var form by rememberSaveable(
        stateSaver = listSaver<TransactionEditorForm, Any?>(
            save = { it.toSaveable() },
            restore = { TransactionEditorForm.fromSaveable(it) ?: initial },
        ),
    ) { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    var saving by rememberSaveable { mutableStateOf(false) }
    var discardOpen by rememberSaveable { mutableStateOf(false) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    var timePickerOpen by rememberSaveable { mutableStateOf(false) }
    var categorySheetOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var subscriptionPickerOpen by rememberSaveable { mutableStateOf(false) }

    val accounts = state.accounts
    val transferMode = form.transferMode(isEdit)
    val valid = form.valid(accounts, isEdit, zone)
    val dirty = form != initial
    val persons = if (existing != null) PersonLists.withCurrent(state.assignablePersons, existing.person) else state.assignablePersons

    val suggestedCategory = remember(form.merchant, state.categories) {
        val enumGuess = MerchantCategorizer.categorize(form.merchant.takeIf { it.isNotBlank() })
        val name = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME[enumGuess]
        state.categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val effectiveCategoryId = form.categoryOverride ?: suggestedCategory?.id
    val effectiveCategory = state.categories.firstOrNull { it.id == effectiveCategoryId }

    fun close() {
        if (dirty && !saving) discardOpen = true else onClose()
    }
    BackHandler(enabled = true) { close() }

    fun save() {
        if (!valid || saving) return
        saving = true
        val value = form.amountValue ?: return
        scope.launch {
            val draft = form.transferDraft(accounts, isEdit, zone)
            val job = if (transferMode && draft != null) {
                viewModel.addTransfer(draft)
            } else if (existing != null) {
                viewModel.updateTransaction(
                    existing, value, form.currency.trim().uppercase(), form.direction(existing),
                    form.merchant.trim().takeIf { it.isNotBlank() }, form.accountId, form.person, effectiveCategoryId,
                    form.timestamp(zone), form.excludeFromStats, form.kind,
                    form.note, form.labelIds,
                )
            } else {
                viewModel.addManual(
                    value, form.currency.trim().uppercase(), form.direction(null),
                    form.merchant.trim().takeIf { it.isNotBlank() }, form.accountId, form.person, effectiveCategoryId,
                    form.timestamp(zone), form.excludeFromStats, form.kind,
                    form.note, form.labelIds,
                )
            }
            job.join()
            // A repeat is a reminder only; it never writes ledger rows (captured payments would count twice).
            if (!isEdit && !transferMode) {
                com.xemmy.mmbank.domain.bills.RepeatPlanner.billFor(
                    repeat = form.repeat, name = form.merchant, fallbackName = form.kind.label, amount = value,
                    currency = form.currency, date = form.date, zone = zone, createdAtMillis = System.currentTimeMillis(),
                )?.let { viewModel.createRepeatBill(it) }
            }
            if (bill != null) viewModel.markBillPaid(bill.id)
            onClose()
        }
    }

    MMScaffold(
        title = if (isEdit) "Edit transaction" else "Add transaction",
        onBack = { close() },
        actions = { TextButton(enabled = valid && !saving, onClick = ::save) { Text("Save") } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isEdit && viewModel.aiParsingConfigured) {
                AiPasteSection(viewModel) { amount, currency, merchant, kind ->
                    form = form.copy(amount = amount, currency = currency, merchant = merchant, kind = kind)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                editorKindsFor(existing?.kind).forEach { entry ->
                    MMFilterChip(label = entry.label, selected = form.kind == entry, onClick = { form = form.copy(kind = entry) })
                }
            }
            Text(kindHint(form.kind, transferMode), style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { form = form.copy(amount = it) },
                    label = { Text("Amount") },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = form.amount.isNotBlank() && form.amountValue == null,
                    modifier = Modifier.weight(if (transferMode) 1f else 0.62f),
                )
                if (!transferMode) {
                    OutlinedTextField(
                        value = form.currency,
                        onValueChange = { form = form.copy(currency = it.uppercase().take(CURRENCY_CODE_MAX_LENGTH)) },
                        label = { Text("Currency") },
                        singleLine = true,
                        modifier = Modifier.weight(0.38f),
                    )
                }
            }
            OutlinedTextField(
                value = form.merchant,
                onValueChange = { form = form.copy(merchant = it) },
                label = { Text(if (transferMode) "Note (optional)" else "Merchant / description (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (transferMode) {
                TransferSection(form, accounts, isEdit, zone) { form = it }
            }

            OutlinedTextField(
                value = form.note,
                onValueChange = { form = form.copy(note = it) },
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!transferMode) {
                LabelPicker(
                    allLabels = allLabels,
                    selected = form.labelIds,
                    onToggle = { id -> form = form.copy(labelIds = if (id in form.labelIds) form.labelIds - id else form.labelIds + id) },
                    onCreate = { name ->
                        scope.launch { viewModel.createLabel(name)?.let { created -> form = form.copy(labelIds = form.labelIds + created.id) } }
                    },
                )
            }

            MMCard(Modifier.fillMaxWidth()) {
                if (!transferMode) {
                    PickerRow(
                        title = "Account",
                        value = accounts.firstOrNull { it.id == form.accountId }?.name ?: "Unassigned",
                        options = listOf<Account?>(null) + accounts,
                        label = { it?.name ?: "Unassigned" },
                        onPick = { form = form.copy(accountId = it?.id) },
                    )
                    ListRow(
                        title = "Category",
                        subtitle = effectiveCategory?.let { "${it.icon} ${it.name}" + if (form.categoryOverride == null) " · auto" else "" } ?: "Uncategorized",
                        onClick = { categorySheetOpen = true },
                    )
                }
                if (!isEdit && !transferMode) {
                    PickerRow(
                        title = "Repeat",
                        value = form.repeat.label,
                        options = com.xemmy.mmbank.domain.bills.Repeat.entries,
                        label = { it.label },
                        onPick = { form = form.copy(repeat = it) },
                    )
                }
                ListRow(title = "Date", subtitle = form.date.format(DATE_LABEL), onClick = { datePickerOpen = true })
                ListRow(title = "Time", subtitle = form.time.format(TIME_LABEL), onClick = { timePickerOpen = true })
                PickerRow(
                    title = "Person",
                    value = form.person ?: "Nobody",
                    options = listOf<String?>(null) + persons,
                    label = { it ?: "Nobody" },
                    onPick = { form = form.copy(person = it) },
                )
            }
            if (!transferMode && form.categoryOverride != null) {
                TextButton(onClick = { form = form.copy(categoryOverride = null) }) { Text("Reset category to auto") }
            }
            if (existing != null && existing.categoryId != null && form.categoryOverride == existing.categoryId) {
                Text(existing.categorySource.explain("category"), style = MaterialTheme.typography.labelSmall, color = MMTheme.colors.textSecondary)
            }
            if (existing != null && form.person != null && form.person == existing.person) {
                Text(existing.personSource.explain("person"), style = MaterialTheme.typography.labelSmall, color = MMTheme.colors.textSecondary)
            }
            if (existing != null && existing.refundOfId != null && existing.kind == TransactionKind.REFUND) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Matched to an earlier purchase from this merchant",
                        style = MaterialTheme.typography.labelSmall,
                        color = MMTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.unlinkRefund(existing); onClose() }) { Text("Not a refund") }
                }
            }

            if (!transferMode) {
                HorizontalDivider(color = MMTheme.colors.borderSubtle)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Exclude from analytics", style = MaterialTheme.typography.bodyLarge)
                        Text("Kept in the ledger, hidden from stats and charts", style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)
                    }
                    Switch(checked = form.excludeFromStats, onCheckedChange = { form = form.copy(excludeFromStats = it) })
                }
            }

            if (existing != null) {
                existing.rawMessage?.takeIf { it.isNotBlank() }?.let { raw ->
                    HorizontalDivider(color = MMTheme.colors.borderSubtle)
                    Text("Original message", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                    Text(raw, style = MaterialTheme.typography.bodySmall)
                }
                if (existing.direction == com.xemmy.mmbank.domain.model.TransactionDirection.DEBIT &&
                    existing.kind == TransactionKind.SPEND
                ) {
                    OutlinedButton(onClick = { onSplitWithPayLater(existing.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Split with Pay Later")
                    }
                    OutlinedButton(onClick = { subscriptionPickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add to subscription")
                    }
                }
                OutlinedButton(onClick = { deleteOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete transaction", color = MMTheme.colors.danger)
                }
            }
        }
    }

    if (subscriptionPickerOpen && existing != null) {
        com.xemmy.mmbank.ui.screens.subscriptions.SubscriptionPickerDialog(
            charge = existing,
            onDismiss = { subscriptionPickerOpen = false },
        )
    }

    if (datePickerOpen) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { form = form.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    datePickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
    if (timePickerOpen) {
        val timeState = rememberTimePickerState(initialHour = form.time.hour, initialMinute = form.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePickerOpen = false },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = { form = form.copy(time = LocalTime.of(timeState.hour, timeState.minute)); timePickerOpen = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timePickerOpen = false }) { Text("Cancel") } },
        )
    }
    if (categorySheetOpen) {
        CategoryPickerBottomSheet(
            categories = state.categories,
            currency = form.currency.trim().ifBlank { state.defaultCurrency },
            onSelect = { form = form.copy(categoryOverride = it.id); categorySheetOpen = false },
            onCreateNew = { name, colorHex, icon, cap, parentId ->
                scope.launch {
                    val created = viewModel.createCategory(name, colorHex, icon, cap, parentId)
                    form = form.copy(categoryOverride = created.id)
                    categorySheetOpen = false
                }
            },
            onDismiss = { categorySheetOpen = false },
            preferIncome = form.direction(existing) == com.xemmy.mmbank.domain.model.TransactionDirection.CREDIT,
        )
    }
    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text("Discard changes?") },
            text = { Text("What you entered here has not been saved.") },
            confirmButton = { TextButton(onClick = { discardOpen = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discardOpen = false }) { Text("Keep editing") } },
        )
    }
    if (deleteOpen && existing != null) {
        DeleteWithIgnoreDialog(
            title = "Delete this transaction?",
            rawMessage = existing.rawMessage,
            merchant = existing.merchant,
            onDelete = { deleteOpen = false; viewModel.delete(existing.id); onClose() },
            onDeleteIgnoreSimilar = { deleteOpen = false; viewModel.deleteAndIgnoreSimilar(existing); onClose() },
            onDeleteIgnoreKeyword = { kw -> deleteOpen = false; viewModel.deleteAndIgnoreKeyword(existing, kw); onClose() },
            onDismiss = { deleteOpen = false },
        )
    }
}

/** Label chips for the row plus "+ New label". Several labels per transaction. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LabelPicker(
    allLabels: List<com.xemmy.mmbank.domain.model.Label>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Labels", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            allLabels.forEach { label ->
                MMFilterChip(label = label.name, selected = label.id in selected, onClick = { onToggle(label.id) })
            }
            MMFilterChip(label = "+ New label", selected = false, onClick = { creating = true })
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New label") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName); newName = ""; creating = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/** A list row whose value is picked from a dropdown. */
@Composable
private fun <T> PickerRow(title: String, value: String, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(title = title, subtitle = value, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

@Composable
private fun TransferSection(
    form: TransactionEditorForm,
    accounts: List<Account>,
    isEdit: Boolean,
    zone: ZoneId,
    onChange: (TransactionEditorForm) -> Unit,
) {
    val from = accounts.firstOrNull { it.id == form.fromAccountId }
    val to = accounts.firstOrNull { it.id == form.toAccountId }
    MMCard(Modifier.fillMaxWidth()) {
        PickerRow(
            title = "From",
            value = from?.let { "${it.name} · ${it.currencyCode}" } ?: "Outside / not tracked",
            options = listOf<Account?>(null) + accounts,
            label = { it?.let { a -> "${a.name} · ${a.currencyCode}" } ?: "Outside / not tracked" },
            onPick = { onChange(form.copy(fromAccountId = it?.id, currency = it?.currencyCode ?: form.currency)) },
        )
        PickerRow(
            title = "To",
            value = to?.let { "${it.name} · ${it.currencyCode}" } ?: "Outside / not tracked",
            options = listOf<Account?>(null) + accounts,
            label = { it?.let { a -> "${a.name} · ${a.currencyCode}" } ?: "Outside / not tracked" },
            onPick = { picked ->
                var next = form.copy(toAccountId = picked?.id)
                // Paying onto a card reads as a card payment.
                if (picked?.type == AccountType.CREDIT_CARD) next = next.copy(kind = TransactionKind.CARD_PAYMENT)
                if (form.fromAccountId == null) picked?.let { next = next.copy(currency = it.currencyCode) }
                onChange(next)
            },
        )
    }
    if (form.crossCurrency(accounts, isEdit)) {
        OutlinedTextField(
            value = form.toAmount,
            onValueChange = { onChange(form.copy(toAmount = it)) },
            label = { Text("Amount received (${to?.currencyCode})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = form.toAmount.isNotBlank() && form.toAmountValue == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (from == null || to == null) {
        Text("Whose account is the other side?", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MMFilterChip(
                label = "Mine, not tracked",
                selected = form.outside == TransferLegs.OutsideParty.MY_OTHER_ACCOUNT,
                onClick = { onChange(form.copy(outside = TransferLegs.OutsideParty.MY_OTHER_ACCOUNT)) },
            )
            MMFilterChip(
                label = "Someone else's",
                selected = form.outside == TransferLegs.OutsideParty.SOMEONE_ELSE,
                onClick = { onChange(form.copy(outside = TransferLegs.OutsideParty.SOMEONE_ELSE)) },
            )
        }
        Text(
            if (form.outside == TransferLegs.OutsideParty.SOMEONE_ELSE) {
                "Counted as Spent or Received, so it shows in your totals."
            } else {
                "A move of your own money: not counted as spent or received."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MMTheme.colors.textSecondary,
        )
    }
    form.transferError(accounts, isEdit, zone)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Add mode only: paste a bank message and let the on-device AI fill the form. */
@Composable
private fun AiPasteSection(
    viewModel: TransactionsViewModel,
    onFill: (amount: String, currency: String, merchant: String, kind: TransactionKind) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var raw by rememberSaveable { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Paste a bank message…") }
        return
    }
    OutlinedTextField(
        value = raw, onValueChange = { raw = it }, label = { Text("Paste bank message") },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = raw.isNotBlank() && !parsing,
            onClick = {
                parsing = true
                error = null
                scope.launch {
                    val fill = viewModel.parseWithAi(raw)
                    if (fill != null) {
                        onFill(fill.amount.stripTrailingZeros().toPlainString(), fill.currency, fill.merchant.orEmpty(), TransactionKind.defaultFor(fill.direction))
                    } else {
                        error = "Couldn't read a transaction. Check the AI server in Settings."
                    }
                    parsing = false
                }
            },
        ) { Text(if (parsing) "Parsing…" else "Parse with AI") }
        if (parsing) AiParsingIndicator(modifier = Modifier.size(18.dp))
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
