package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xemmy.mmbank.domain.model.TransactionKind
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.util.rememberReduceMotion
import com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Read-only detail overlay for a tapped transaction. Built as an [AlertDialog]
 * — a real dialog window — rather than a ModalBottomSheet: only a dialog window
 * lets [GlassDialogBackdrop] apply the API 31+ backdrop blur (its view.parent is
 * a DialogWindowProvider), and the glass panel + hairline read correctly on top.
 * The earlier bottom-sheet incarnation could never blur its backdrop and rendered
 * as a translucent, then empty, panel on some devices; this mirrors the editor
 * dialog's proven treatment exactly.
 *
 * That same separate-window boundary is why this uses a spring scale-in rather
 * than a true `SharedTransitionLayout` element shared with the tapped row:
 * shared elements only resolve within one `LookaheadScope`, and a Dialog's
 * content is composed into its own window, outside the app window's scope.
 * The spring pop (Premium only; Classic is instant, as before) is the closest
 * same-window-safe stand-in for that continuity.
 */
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    accountName: String?,
    /** Friendly bank name resolved from the sender (user label, then built-in map). */
    bankName: String?,
    /** Dynamic categories, to resolve [Transaction.categoryId] for the Category row. */
    categories: List<Category>,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit,
    onMerchantClick: (String) -> Unit = {},
    /** Re-classifies the row; the flag asks to remember the kind for its merchant. Null hides the picker. */
    onKindChange: ((TransactionKind, Boolean) -> Unit)? = null,
    /** Set when this charge is far above the merchant's usual (see [SpendAnomalyDetector]). */
    anomaly: SpendAnomalyDetector.Anomaly? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Glass overlay: translucent container + hairline border, with a real
        // backdrop blur on API 31+ (falls back to a more opaque surface below).
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val merchant = transaction.merchant?.takeIf { it.isNotBlank() }
                var titleModifier = Modifier.weight(1f, fill = false)
                if (merchant != null) titleModifier = titleModifier.clickable { onMerchantClick(merchant) }
                Text(
                    merchant ?: transaction.sender ?: transaction.source.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ObsidianTokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier,
                )
                Spacer(Modifier.width(12.dp))
                AmountText(
                    display = (if (transaction.direction == TransactionDirection.CREDIT) "+" else "-") +
                        transaction.amount.asMoney(transaction.currencyCode),
                    color = when (amountTone(transaction.amount, transaction.direction)) {
                        AmountTone.NEGATIVE -> amountNegativeColor
                        AmountTone.POSITIVE -> amountPositiveColor
                        AmountTone.NEUTRAL -> ObsidianTokens.textPrimary
                    },
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        },
        text = {
            GlassDialogBackdrop()
            val reduceMotion = rememberReduceMotion()
            val entranceScale = remember { Animatable(if (reduceMotion) 1f else 0.92f) }
            LaunchedEffect(reduceMotion) {
                if (!reduceMotion) {
                    entranceScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                } else {
                    entranceScale.snapTo(1f)
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .graphicsLayer {
                        scaleX = entranceScale.value
                        scaleY = entranceScale.value
                    }
                    .verticalScroll(rememberScrollState()),
            ) {
                anomaly?.let { a ->
                    Text(
                        "⚠ ${String.format(java.util.Locale.US, "%.1f", a.ratio)}× your usual at ${a.merchant} " +
                            "(usually about ${a.usual.asMoney(a.currencyCode)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ObsidianTokens.debit,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val assignedCategory = categories.firstOrNull { it.id == transaction.categoryId }
                DetailRow(
                    "Category",
                    assignedCategory?.let { "${it.icon}  ${it.name}" } ?: "🏷️  Uncategorized",
                )
                DetailRow("Account", accountName ?: "Unassigned")
                DetailRow(
                    "Type",
                    transaction.kind.label + if (transaction.linkedTransactionId != null) " · linked" else "",
                )
                if (onKindChange != null) {
                    KindPicker(transaction, onKindChange)
                }
                DetailRow("Date & time", transaction.timestampMillis.asDateTime())
                (bankName ?: transaction.sender)?.let { DetailRow("Sender", it) }
                DetailRow("Source", sourceLabel(transaction.source))

                transaction.rawMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    HorizontalDivider(color = ObsidianTokens.hairlineDialog, modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        "Message",
                        style = MaterialTheme.typography.labelMedium,
                        color = ObsidianTokens.textMeta,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ObsidianTokens.textPrimary,
                    )
                }

                if (transaction.excludeFromStats) {
                    Text(
                        "⚠ Excluded from analytics",
                        style = MaterialTheme.typography.labelMedium,
                        color = ObsidianTokens.textMeta,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onEditClick) {
                Text("Edit")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDeleteClick,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
    )
}

/**
 * Re-classify in place: "this isn't spending, it's a card payment". With a
 * merchant, the change is remembered for it by default -- the correction the
 * user is making is almost always about the merchant, not the one row.
 */
@Composable
private fun KindPicker(transaction: Transaction, onKindChange: (TransactionKind, Boolean) -> Unit) {
    val merchant = transaction.merchant?.trim()?.takeIf { it.isNotEmpty() }
    var rememberForMerchant by remember(transaction.id) { mutableStateOf(merchant != null) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            editorKindsFor(transaction.kind)
                // A one-sided kind on the wrong side would be coerced away anyway.
                .filter { it.fixedDirection == null || it.fixedDirection == transaction.direction }
                .forEach { kind ->
                    ObsidianChip(
                        selected = transaction.kind == kind,
                        onClick = { if (kind != transaction.kind) onKindChange(kind, rememberForMerchant && merchant != null) },
                        label = { Text(kind.label) },
                    )
                }
        }
        if (merchant != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberForMerchant, onCheckedChange = { rememberForMerchant = it })
                Text(
                    "Remember for $merchant",
                    style = MaterialTheme.typography.bodySmall,
                    color = ObsidianTokens.textMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = ObsidianTokens.textMeta,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ObsidianTokens.textPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
