package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MMbankTheme
import com.xemmy.mmbank.ui.theme.rowEdgeGlow

/** Row heights from the plan: comfortable 72dp (two lines), compact 56dp (Home "Recent"). */
enum class TransactionRowDensity(val minHeightDp: Int) {
    COMPACT(56),
    COMFORTABLE(72),
}

/**
 * Display model for one ledger row. Precomputed by the ViewModel so the row
 * does no formatting work while scrolling (Improvement Plan §4.2).
 *
 * [amount] is the magnitude; the sign comes from [kind]. A null or blank
 * [categoryName] means uncategorised. [timeLabel] is already formatted
 * ("14:20", "11 Nov").
 */
@Immutable
data class TransactionRowUi(
    val id: Long,
    val merchant: String,
    val amount: Double,
    val currencyCode: String,
    val kind: TransactionKind,
    val categoryName: String? = null,
    val accountLabel: String? = null,
    val timeLabel: String? = null,
    val isPending: Boolean = false,
    val isExcluded: Boolean = false,
)

/** A small status tag shown beside the subtitle. A null [tone] renders the neutral tag. */
data class TransactionRowTag(val label: String, val tone: MMTone? = null)

/** Alpha for excluded rows (plan: 60% alpha plus an "Excluded" tag, no strikethrough). */
const val EXCLUDED_ROW_ALPHA = 0.6f

private const val SUBTITLE_SEPARATOR = " · "
private const val UNCATEGORISED = "Uncategorised"

/** Transfers and card payments move money between the user's own accounts and need no category. */
private fun TransactionKind.needsNoCategory(): Boolean =
    this == TransactionKind.TRANSFER || this == TransactionKind.CARD_PAYMENT

private fun TransactionRowUi.category(): String? = categoryName?.takeIf { it.isNotBlank() }

private fun TransactionRowUi.account(): String? = accountLabel?.takeIf { it.isNotBlank() }

fun isUncategorised(row: TransactionRowUi): Boolean =
    row.category() == null && !row.kind.needsNoCategory()

/** "Groceries · ADCB ••1234"; internal moves lead with the kind instead of a category. */
fun transactionRowSubtitle(row: TransactionRowUi): String {
    val first = if (row.kind.needsNoCategory()) row.kind.label else row.category() ?: UNCATEGORISED
    return listOfNotNull(first, row.account()).joinToString(SUBTITLE_SEPARATOR)
}

/** Status tags in display order. Colour is never the only signal: each tag carries a word. */
fun transactionRowTags(row: TransactionRowUi): List<TransactionRowTag> = buildList {
    if (row.isPending) add(TransactionRowTag("Pending", MMTone.WARNING))
    if (isUncategorised(row)) add(TransactionRowTag(UNCATEGORISED, MMTone.INFO))
    if (row.isExcluded) add(TransactionRowTag("Excluded"))
}

/** First letter or digit of the merchant for the default leading tile; "?" when there is none. */
fun merchantInitial(merchant: String): String =
    merchant.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/**
 * One spoken sentence for TalkBack, e.g.
 * "Carrefour, spent 86 dirhams 40 fils, Groceries, ADCB ••1234, 14:20, pending".
 * The amount is hidden when [hidden] so the sentence never leaks a figure.
 */
fun transactionRowDescription(row: TransactionRowUi, hidden: Boolean): String {
    val parts = mutableListOf<String>()
    parts += row.merchant.ifBlank { "Unknown merchant" }
    parts += moneyContentDescription(row.amount, row.currencyCode, row.kind, hidden)
    if (!row.kind.needsNoCategory()) parts += row.category() ?: UNCATEGORISED.lowercase()
    row.account()?.let { parts += it }
    row.timeLabel?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (row.isPending) parts += "pending"
    if (row.isExcluded) parts += "excluded from insights"
    return parts.joinToString(", ")
}

/** True when the row needs a click target at all (tap or long-press). */
fun transactionRowIsInteractive(hasClick: Boolean, hasLongClick: Boolean): Boolean = hasClick || hasLongClick

/** TalkBack label announced for the long-press action (bulk select). */
const val LONG_CLICK_LABEL = "Select"

/**
 * The ledger row: leading tile, merchant, "category · account" subtitle with
 * status tags, and the amount over the time. The whole row is one TalkBack
 * sentence and, with [onClick], one button. [onLongClick] adds the long-press
 * action (bulk select); [glowTone] paints the brand-origin edge glow (spec 5.3).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(
    row: TransactionRowUi,
    modifier: Modifier = Modifier,
    density: TransactionRowDensity = TransactionRowDensity.COMFORTABLE,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    glowTone: GlowTone? = null,
) {
    val colors = MMTheme.colors
    val description = transactionRowDescription(row, LocalHideBalances.current)
    val tags = transactionRowTags(row)
    val base = if (transactionRowIsInteractive(onClick != null, onLongClick != null)) {
        modifier.combinedClickable(
            role = Role.Button,
            onLongClickLabel = if (onLongClick != null) LONG_CLICK_LABEL else null,
            onLongClick = onLongClick,
            onClick = onClick ?: {},
        )
    } else {
        modifier
    }
    Row(
        modifier = base
            .rowEdgeGlow(glowTone)
            .fillMaxWidth()
            .alpha(if (row.isExcluded) EXCLUDED_ROW_ALPHA else 1f)
            .heightIn(min = density.minHeightDp.dp)
            .padding(horizontal = MMSpacing.gutter, vertical = MMSpacing.sm)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MMSpacing.md),
    ) {
        if (leading != null) leading() else TransactionRowLeadingTile(merchantInitial(row.merchant))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.merchant,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MMSpacing.sm),
            ) {
                Text(
                    text = transactionRowSubtitle(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                for (tag in tags) MMTag(text = tag.label, tone = tag.tone)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(amount = row.amount, currencyCode = row.currencyCode, kind = row.kind)
            val time = row.timeLabel
            if (time != null && density == TransactionRowDensity.COMFORTABLE) {
                Text(text = time, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun TransactionRowLeadingTile(initial: String) {
    val colors = MMTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(colors.surfaceRaised, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initial, style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
    }
}

@MMPreviews
@Composable
private fun TransactionRowPreview() {
    MMbankTheme {
        Column(Modifier.background(MMTheme.colors.surface)) {
            TransactionRow(
                TransactionRowUi(1, "Carrefour", 86.40, "AED", TransactionKind.SPEND, "Groceries", "ADCB ••1234", "14:20"),
                onClick = {},
            )
            TransactionRow(
                TransactionRowUi(2, "Salary", 12000.0, "AED", TransactionKind.INCOME, "Income", "ADCB ••1234", "09:00"),
            )
            TransactionRow(
                TransactionRowUi(3, "Noon", 45.0, "AED", TransactionKind.SPEND, null, "Tabby", "11 Nov", isPending = true),
            )
            TransactionRow(
                TransactionRowUi(4, "To savings", 500.0, "AED", TransactionKind.TRANSFER, null, "ADCB ••1234", "10 Nov"),
            )
            TransactionRow(
                TransactionRowUi(5, "Uber", 23.5, "AED", TransactionKind.SPEND, "Transport", "ADCB ••1234", "09 Nov", isExcluded = true),
                density = TransactionRowDensity.COMPACT,
            )
        }
    }
}
