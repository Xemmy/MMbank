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
import com.xemmy.mmbank.domain.model.DesignMode
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.LocalDesignMode
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
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit,
    onMerchantClick: (String) -> Unit = {},
    designMode: DesignMode = LocalDesignMode.current,
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
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        },
        text = {
            GlassDialogBackdrop()
            val entranceScale = remember { Animatable(if (designMode == DesignMode.PREMIUM) 0.92f else 1f) }
            LaunchedEffect(designMode) {
                if (designMode == DesignMode.PREMIUM) {
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
                DetailRow("Category", "${transaction.spendingCategory.emoji}  ${transaction.spendingCategory.label}")
                DetailRow("Account", accountName ?: "Unassigned")
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
