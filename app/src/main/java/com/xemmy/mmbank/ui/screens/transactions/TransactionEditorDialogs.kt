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
