package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    accountName: String?,
    /** Friendly bank name resolved from the sender (user label, then built-in map). */
    bankName: String?,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.glassSurface(
            RoundedCornerShape(topStart = ObsidianTokens.dialogRadius, topEnd = ObsidianTokens.dialogRadius),
        ),
        containerColor = Color.Transparent,
        contentColor = ObsidianTokens.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        GlassDialogBackdrop()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    transaction.merchant ?: transaction.sender ?: transaction.source.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ObsidianTokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
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

            HorizontalDivider(color = ObsidianTokens.hairlineDialog, modifier = Modifier.padding(vertical = 12.dp))

            DetailRow("Category", "${transaction.spendingCategory.emoji}  ${transaction.spendingCategory.label}")
            DetailRow("Account", accountName ?: "Unassigned")
            DetailRow("Date & time", transaction.timestampMillis.asDateTime())
            (bankName ?: transaction.sender)?.let { DetailRow("Sender", it) }
            DetailRow("Source", sourceLabel(transaction.source))

            transaction.rawMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    "Message",
                    style = MaterialTheme.typography.labelMedium,
                    color = ObsidianTokens.textMeta,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                    Text("Edit")
                }
                TextButton(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete")
                }
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
