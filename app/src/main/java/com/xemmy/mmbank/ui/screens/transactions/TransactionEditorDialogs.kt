package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category as CategoryIcon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import com.xemmy.mmbank.ui.util.rememberCurrentDate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityDrillDown
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.ai.AiResponseParser
import com.xemmy.mmbank.domain.ai.AiTransactionPrompt
import com.xemmy.mmbank.domain.parser.GenericExtractors
import com.xemmy.mmbank.domain.parser.IgnoreKeywordSuggester
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.usecase.BulkTransactionPatch
import com.xemmy.mmbank.domain.usecase.FieldUpdate
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.AiParsingIndicator
import com.xemmy.mmbank.ui.components.CategoryEditorDialog
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.GlowPalette
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.RowAvatar
import com.xemmy.mmbank.ui.theme.dottedLeader
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.theme.rowEdgeGlow
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MAX_LENGTH
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MIN_LENGTH
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.dayPillLabel
import com.xemmy.mmbank.ui.util.recoverAndRetry
import com.xemmy.mmbank.ui.util.toLocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
internal fun NewPersonDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign person") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add or edit a transaction. With [existing] null it hand-enters one the capture
 * pipeline missed; with [existing] set it edits that row's fields in place. Either
 * way the result behaves identically to a captured row in balances and filters.
 */
@Composable
internal fun TransactionEditorDialog(
    existing: Transaction?,
    accounts: List<Account>,
    knownPersons: List<String>,
    categories: List<Category>,
    defaultCurrency: String,
    aiEnabled: Boolean = false,
    onAiParse: (suspend (String) -> AiEditorFill?)? = null,
    onCreateCategory: suspend (
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: java.math.BigDecimal?,
        parentCategoryId: Long?,
    ) -> Category,
    onDismiss: () -> Unit,
    onSave: (
        amount: java.math.BigDecimal,
        currency: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        categoryId: Long?,
        timestampMillis: Long,
        excludeFromStats: Boolean,
        kind: TransactionKind,
    ) -> Unit,
    /** Add mode only: saves a transfer/card payment as two linked legs. */
    onSaveTransfer: ((TransferLegs.Draft) -> Unit)? = null,
    /** Edit mode: breaks a refund's link to the purchase it gave money back for ("Not a refund"). */
    onUnlinkRefund: ((Transaction) -> Unit)? = null,
) {
    val zone = java.time.ZoneId.systemDefault()
    val isEdit = existing != null
    var amount by remember { mutableStateOf(existing?.amount?.stripTrailingZeros()?.toPlainString() ?: "") }
    var currency by remember { mutableStateOf(existing?.currencyCode ?: defaultCurrency) }
    var kind by remember { mutableStateOf(existing?.kind ?: TransactionKind.SPEND) }
    // Only consulted for a kind that goes either way (an edited transfer leg
    // or correction keeps the side it was on); everything else follows the kind.
    val direction = kind.fixedDirection ?: existing?.direction ?: TransactionDirection.DEBIT
    // Adding a transfer/card payment means two accounts and two legs.
    val transferMode = !isEdit && onSaveTransfer != null && kind.isInternalMove
    var fromAccountId by remember { mutableStateOf<Long?>(null) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var toAmount by remember { mutableStateOf("") }
    var outsideParty by remember { mutableStateOf(TransferLegs.OutsideParty.MY_OTHER_ACCOUNT) }
    var fromMenuOpen by remember { mutableStateOf(false) }
    var toMenuOpen by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf(existing?.merchant.orEmpty()) }
    var accountId by remember { mutableStateOf(existing?.accountId) }
    var person by remember { mutableStateOf(existing?.person) }
    // null = follow the merchant-derived suggestion; a value = explicit choice.
    // Editing starts from the row's saved category so it never silently changes.
    var categoryIdOverride by remember { mutableStateOf(existing?.categoryId) }
    var creatingCategory by remember { mutableStateOf(false) }
    val categoryScope = rememberCoroutineScope()
    var excludeFromStats by remember { mutableStateOf(existing?.excludeFromStats ?: false) }
    var dateIso by remember {
        mutableStateOf(
            java.time.Instant.ofEpochMilli(existing?.timestampMillis ?: System.currentTimeMillis())
                .atZone(zone).toLocalDate().toString()
        )
    }

    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }

    val amountValue = com.xemmy.mmbank.util.AmountInput.parse(amount)
    val date = runCatching { java.time.LocalDate.parse(dateIso.trim()) }.getOrNull()
    val fromAccount = accounts.firstOrNull { it.id == fromAccountId }
    val toAccount = accounts.firstOrNull { it.id == toAccountId }
    // A second amount only when the money changes currency on the way.
    val crossCurrency = transferMode && fromAccount != null && toAccount != null &&
        !fromAccount.currencyCode.equals(toAccount.currencyCode, ignoreCase = true)
    val toAmountValue = com.xemmy.mmbank.util.AmountInput.parse(toAmount)
    fun endpoint(account: Account?) = account?.let {
        TransferLegs.Endpoint(it.id, it.name, it.type, it.currencyCode)
    }
    val transferDraft = if (transferMode && amountValue != null && date != null) {
        TransferLegs.Draft(
            from = endpoint(fromAccount),
            to = endpoint(toAccount),
            amount = amountValue,
            toAmount = if (crossCurrency) toAmountValue else null,
            timestampMillis = 0,
            note = merchant,
            person = person,
            outside = outsideParty,
        )
    } else {
        null
    }
    val transferError = transferDraft?.let(TransferLegs::validate)
        ?: if (crossCurrency && toAmountValue == null) "Enter the amount received" else null
    val valid = amountValue != null && amountValue.signum() > 0 &&
        currency.trim().length in CURRENCY_CODE_MIN_LENGTH..CURRENCY_CODE_MAX_LENGTH && date != null &&
        (!transferMode || (transferDraft != null && transferError == null))
    val accountName = accounts.firstOrNull { it.id == accountId }?.name
    // Same merchant-keyword guess the capture pipeline uses, bridged onto its
    // same-named dynamic category (see CategoryMigrationMapping) so "Auto"
    // previews a real, selectable category rather than a bare enum label.
    val suggestedCategory = remember(merchant, categories) {
        val enumGuess = MerchantCategorizer.categorize(merchant.takeIf { it.isNotBlank() })
        val name = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME[enumGuess]
        categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val effectiveCategoryId = categoryIdOverride ?: suggestedCategory?.id

    AlertDialog(
        onDismissRequest = onDismiss,
        // Glass overlay: translucent container + hairline border, with a real
        // backdrop blur on API 31+ (falls back to a more opaque surface below).
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = { Text(if (isEdit) "Edit transaction" else "Add transaction", style = MaterialTheme.typography.titleLarge) },
        text = {
            GlassDialogBackdrop()
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Manual "Parse with AI" (add mode only): paste a bank message
                // the built-in rules missed and let the local (loopback-only) AI
                // fill amount/currency/merchant/direction. Only shown when the
                // feature is configured; failures leave the form untouched.
                if (!isEdit && aiEnabled && onAiParse != null) {
                    val aiParse = onAiParse
                    val aiScope = rememberCoroutineScope()
                    var rawMessage by remember { mutableStateOf("") }
                    var parsing by remember { mutableStateOf(false) }
                    var parseError by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        value = rawMessage,
                        onValueChange = { rawMessage = it },
                        label = { MetaLabel("Paste bank message") },
                        minLines = 2,
                        colors = glassFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = rawMessage.isNotBlank() && !parsing,
                            onClick = {
                                parsing = true
                                parseError = null
                                aiScope.launch {
                                    val fill = aiParse(rawMessage)
                                    if (fill != null) {
                                        amount = fill.amount.stripTrailingZeros().toPlainString()
                                        currency = fill.currency
                                        merchant = fill.merchant.orEmpty()
                                        kind = TransactionKind.defaultFor(fill.direction)
                                    } else {
                                        parseError = "Couldn't read a transaction. Check the AI server in Settings."
                                    }
                                    parsing = false
                                }
                            },
                        ) { Text(if (parsing) "Parsing…" else "Parse with AI") }
                        if (parsing) {
                            AiParsingIndicator(modifier = Modifier.size(18.dp))
                        }
                    }
                    parseError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider(color = MMTheme.colors.borderSubtle)
                }
                // What it is: decides the sign in balances AND how it counts in
                // Spent/Received (a refund lowers spending, a transfer or card
                // payment counts in neither). See LedgerMath.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    editorKindsFor(existing?.kind).forEach { entry ->
                        MMFilterChip(
                            selected = kind == entry,
                            onClick = { kind = entry },
                            label = entry.label,
                        )
                    }
                }
                Text(
                    kindHint(kind, transferMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { MetaLabel("Amount") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = AppFonts.plexMono),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        isError = amount.isNotBlank() && amountValue == null,
                        colors = glassFieldColors(),
                        modifier = if (transferMode) Modifier.weight(1f) else Modifier.weight(0.6f),
                    )
                    // Transfers take the currency from the chosen account.
                    if (!transferMode) OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(CURRENCY_CODE_MAX_LENGTH) },
                        label = { MetaLabel("Currency") },
                        singleLine = true,
                        colors = glassFieldColors(),
                        modifier = Modifier.weight(0.4f),
                    )
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { MetaLabel("Merchant / description (optional)") },
                    singleLine = true,
                    colors = glassFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateIso,
                    onValueChange = { dateIso = it },
                    label = { MetaLabel("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = date == null,
                    colors = glassFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (transferMode) {
                    TransferAccountPicker(
                        label = "From",
                        accounts = accounts,
                        selectedId = fromAccountId,
                        open = fromMenuOpen,
                        onOpenChange = { fromMenuOpen = it },
                        onSelect = { picked ->
                            fromAccountId = picked?.id
                            picked?.let { currency = it.currencyCode }
                        },
                    )
                    TransferAccountPicker(
                        label = "To",
                        accounts = accounts,
                        selectedId = toAccountId,
                        open = toMenuOpen,
                        onOpenChange = { toMenuOpen = it },
                        onSelect = { picked ->
                            toAccountId = picked?.id
                            // Paying onto a card reads as a card payment.
                            if (picked?.type == com.xemmy.mmbank.domain.model.AccountType.CREDIT_CARD) {
                                kind = TransactionKind.CARD_PAYMENT
                            }
                            if (fromAccountId == null) picked?.let { currency = it.currencyCode }
                        },
                    )
                    if (crossCurrency) {
                        OutlinedTextField(
                            value = toAmount,
                            onValueChange = { toAmount = it },
                            label = { MetaLabel("Amount received (${toAccount?.currencyCode})") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            isError = toAmount.isNotBlank() && toAmountValue == null,
                            colors = glassFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (fromAccount == null || toAccount == null) {
                        // One side is outside the app: is it the user's own money or someone else's?
                        MetaLabel("Whose account is the other side?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MMFilterChip(
                                selected = outsideParty == TransferLegs.OutsideParty.MY_OTHER_ACCOUNT,
                                onClick = { outsideParty = TransferLegs.OutsideParty.MY_OTHER_ACCOUNT },
                                label = "Mine, not tracked",
                            )
                            MMFilterChip(
                                selected = outsideParty == TransferLegs.OutsideParty.SOMEONE_ELSE,
                                onClick = { outsideParty = TransferLegs.OutsideParty.SOMEONE_ELSE },
                                label = "Someone else's",
                            )
                        }
                        Text(
                            if (outsideParty == TransferLegs.OutsideParty.SOMEONE_ELSE) {
                                "Counted as Spent or Received, so it shows in your totals."
                            } else {
                                "A move of your own money: not counted as spent or received."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    transferError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // Account picker — same target the capture pipeline assigns to.
                    // A glowing brand dot previews the row's edge glow before saving.
                    Box {
                        val accountTone = GlowTones.forAccountOrigin(
                            accountName,
                            accounts.firstOrNull { it.id == accountId }?.bankName,
                        ) ?: GlowTone.SYSTEM_SLATE
                        OutlinedButton(onClick = { accountMenuOpen = true }) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(GlowPalette.color(accountTone)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Account: " + (accountName ?: "Unassigned"))
                        }
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Unassigned") },
                                onClick = { accountMenuOpen = false; accountId = null },
                            )
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = { accountMenuOpen = false; accountId = account.id },
                                )
                            }
                        }
                    }
                }

                Box {
                    OutlinedButton(onClick = { personMenuOpen = true }) {
                        Text("Person: " + (person ?: "Nobody"))
                    }
                    DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nobody") },
                            onClick = { personMenuOpen = false; person = null },
                        )
                        knownPersons.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { personMenuOpen = false; person = name },
                            )
                        }
                    }
                }
                // A refund that was matched to an earlier purchase says so, and can be un-matched.
                if (existing != null && existing.refundOfId != null && existing.kind == TransactionKind.REFUND && onUnlinkRefund != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Matched to an earlier purchase from this merchant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUnlinkRefund(existing); onDismiss() }) { Text("Not a refund") }
                    }
                }
                // Why this person is what it is -- only while it is still what the app stored.
                if (existing != null && person != null && person == existing.person) {
                    Text(
                        existing.personSource.explain("person"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!transferMode) {
                // Category chip row — defaults to the merchant-derived guess
                // ("Auto"), overridable, plus a trailing "+ New" chip opening
                // the shared create form. Mirrors the auto-categorization done
                // on capture. Active chip reads teal (spec 5.3).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetaLabel("Category")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MMFilterChip(
                            selected = categoryIdOverride == null,
                            onClick = { categoryIdOverride = null },
                            label = suggestedCategory?.let { "Auto · ${it.icon} ${it.name}" } ?: "Auto · Uncategorized",
                        )
                        categories.forEach { category ->
                            MMFilterChip(
                                selected = categoryIdOverride == category.id,
                                onClick = { categoryIdOverride = category.id },
                                label = "${category.icon} ${category.name}",
                            )
                        }
                        MMFilterChip(
                            selected = false,
                            onClick = { creatingCategory = true },
                            label = "+ New",
                        )
                    }
                    // Why this category is what it is -- only while it is still what the app stored.
                    if (existing != null && existing.categoryId != null && categoryIdOverride == existing.categoryId) {
                        Text(
                            existing.categorySource.explain("category"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                androidx.compose.material3.HorizontalDivider(color = MMTheme.colors.borderSubtle)

                // Analytics purity control: internal transfers, cash refunds and
                // the like stay in the ledger but shouldn't skew the stats.
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Exclude from Analytics", style = MaterialTheme.typography.bodyLarge)
                        MetaLabel("Kept in ledger, hidden from stats & charts", maxLines = 2)
                    }
                    Switch(
                        checked = excludeFromStats,
                        onCheckedChange = { excludeFromStats = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MMTheme.colors.moneyIn),
                    )
                }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                enabled = valid,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MMTheme.colors.textPrimary,
                    contentColor = MMTheme.colors.canvas,
                ),
                onClick = {
                    // Button is only enabled when both parse, so this is safe.
                    val parsedDate = date
                    val value = amountValue
                    if (parsedDate != null && value != null) {
                        // Keep the original time-of-day when editing (so unchanged
                        // dates keep their exact timestamp and intra-day order);
                        // use the current time for a fresh manual entry.
                        val timeOfDay = existing
                            ?.let { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalTime() }
                            ?: java.time.LocalTime.now()
                        val timestamp = parsedDate.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                        if (transferMode && transferDraft != null && onSaveTransfer != null) {
                            onSaveTransfer(transferDraft.copy(timestampMillis = timestamp))
                        } else {
                            onSave(
                                value,
                                currency.trim().uppercase(),
                                direction,
                                merchant.trim().takeIf { it.isNotBlank() },
                                accountId,
                                person,
                                effectiveCategoryId,
                                timestamp,
                                excludeFromStats,
                                kind,
                            )
                        }
                    }
                },
            ) { Text(if (isEdit) "Save changes" else if (transferMode) "Add ${kind.label.lowercase()}" else "Add transaction") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (creatingCategory) {
        CategoryEditorDialog(
            initial = null,
            currency = currency.trim().ifBlank { defaultCurrency },
            allCategories = categories,
            onDismiss = { creatingCategory = false },
            onSave = { name, icon, colorHex, cap, parentId ->
                categoryScope.launch {
                    val created = onCreateCategory(name, colorHex, icon, cap, parentId)
                    categoryIdOverride = created.id
                    creatingCategory = false
                }
            },
        )
    }
}

/** The kinds offered when adding/editing by hand, plus [current] when it's one of the rarer ones. */
internal fun editorKindsFor(current: TransactionKind?): List<TransactionKind> {
    val base = listOf(
        TransactionKind.SPEND,
        TransactionKind.INCOME,
        TransactionKind.REFUND,
        TransactionKind.TRANSFER,
        TransactionKind.CARD_PAYMENT,
        TransactionKind.ATM,
    )
    return if (current == null || current in base) base else base + current
}

/** One line under the kind chips saying how the choice counts. */
internal fun kindHint(kind: TransactionKind, transferMode: Boolean): String = when (kind) {
    TransactionKind.SPEND -> "Counts as spending"
    TransactionKind.ATM -> "Cash taken out — counts as spending"
    TransactionKind.INCOME -> "Counts as money received"
    TransactionKind.REFUND -> "Money back — lowers spending, not income"
    TransactionKind.TRANSFER ->
        if (transferMode) "Between your accounts — saved as two linked sides, not spending"
        else "Between your accounts — not spending or income"
    TransactionKind.CARD_PAYMENT ->
        if (transferMode) "Paying a card off — saved as two linked sides; purchases already counted"
        else "Paying a card off — purchases on the card already counted"
    TransactionKind.CASHBACK -> "Rewards — shown on Cashback, not in totals"
    TransactionKind.ADJUSTMENT -> "Balance correction — not in totals"
}

@Composable
internal fun TransferAccountPicker(
    label: String,
    accounts: List<Account>,
    selectedId: Long?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (Account?) -> Unit,
) {
    val selected = accounts.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { onOpenChange(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: " + (selected?.let { "${it.name} · ${it.currencyCode}" } ?: "Outside / not tracked"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            DropdownMenuItem(
                text = { Text("Outside / not tracked") },
                onClick = { onOpenChange(false); onSelect(null) },
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("${account.name} · ${account.currencyCode}") },
                    onClick = { onOpenChange(false); onSelect(account) },
                )
            }
        }
    }
}

@Composable
internal fun RenameSenderDialog(
    sender: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var label by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Show a friendly name for “$sender” everywhere it appears.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onSave(label.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset to default") } },
    )
}

/**
 * Delete confirmation that doubles as "this message was wrong": besides a
 * plain delete it can also mute future look-alikes ([onDeleteIgnoreSimilar])
 * or any message containing a phrase ([onDeleteIgnoreKeyword]). The ignore
 * options only show for captured messages, i.e. when [rawMessage] is present;
 * pass null for [onDeleteIgnoreKeyword] (bulk) to hide the keyword option.
 */
@Composable
internal fun DeleteWithIgnoreDialog(
    title: String,
    rawMessage: String?,
    merchant: String?,
    onDelete: () -> Unit,
    onDeleteIgnoreSimilar: () -> Unit,
    onDeleteIgnoreKeyword: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var keywordMode by remember { mutableStateOf(false) }
    var keyword by remember(rawMessage, merchant) {
        mutableStateOf(IgnoreKeywordSuggester.suggest(rawMessage, merchant))
    }
    val canIgnore = !rawMessage.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This can't be undone.")
                if (canIgnore) {
                    Text(
                        "Captured wrongly? You can also stop similar messages from being captured again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (keywordMode && onDeleteIgnoreKeyword != null) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("Ignore messages containing") },
                        supportingText = { Text("Must appear in this message (3+ characters)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canIgnore) {
                    if (keywordMode && onDeleteIgnoreKeyword != null) {
                        TextButton(
                            enabled = IgnoreKeywordSuggester.isValid(keyword, rawMessage),
                            onClick = { onDeleteIgnoreKeyword(keyword.trim()) },
                        ) { Text("Delete & ignore \"${keyword.trim()}\"") }
                    } else {
                        TextButton(onClick = onDeleteIgnoreSimilar) { Text("Delete & ignore similar") }
                        if (onDeleteIgnoreKeyword != null) {
                            TextButton(onClick = { keywordMode = true }) { Text("Delete & ignore containing…") }
                        }
                    }
                }
                TextButton(onClick = onDelete) { Text("Delete only") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
