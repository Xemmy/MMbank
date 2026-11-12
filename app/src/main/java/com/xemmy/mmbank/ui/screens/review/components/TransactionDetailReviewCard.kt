package com.xemmy.mmbank.ui.screens.review.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.CategorySuggestion
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.screens.review.ConfidenceLevel
import com.xemmy.mmbank.ui.theme.MMColors
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asQuantity

/** Alpha of the tinted background behind highlighted amount/merchant text in the raw message. */
private const val HIGHLIGHT_ALPHA = 0.35f

private fun ConfidenceLevel.color(colors: MMColors): Color = when (this) {
    ConfidenceLevel.HIGH -> colors.moneyIn
    ConfidenceLevel.MEDIUM -> colors.warning
    ConfidenceLevel.LOW -> colors.danger
}

private fun confidenceLabel(score: Int?): String = "${score ?: 0}% confident"

/** SMS-style date/time tokens: "12/07/2026", "12-Jul-2026", "10:45". Best-effort, no locale parsing. */
private val DATE_TOKEN_REGEX = Regex(
    "\\b\\d{1,2}[-/][A-Za-z]{3,9}[-/]?\\d{0,4}\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b|\\b\\d{1,2}:\\d{2}\\b",
)

/**
 * Every index range in [rawText] worth highlighting in the raw SMS inspector:
 * the [amount] and [merchant] strings wherever they occur, plus any
 * date/time-shaped token. Pure and top-level so it's JVM-testable without a
 * running Compose UI tree; the composable below turns the ranges into styled
 * spans.
 */
internal fun highlightRanges(rawText: String, amount: String?, merchant: String?): List<IntRange> {
    if (rawText.isBlank()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    listOfNotNull(amount, merchant).filter { it.isNotBlank() }.forEach { term ->
        var index = rawText.indexOf(term, ignoreCase = true)
        while (index >= 0) {
            ranges += index until (index + term.length)
            index = rawText.indexOf(term, startIndex = index + term.length, ignoreCase = true)
        }
    }
    DATE_TOKEN_REGEX.findAll(rawText).forEach { match -> if (match.range.last >= match.range.first) ranges += match.range }
    return mergeOverlapping(ranges.sortedBy { it.first })
}

private fun mergeOverlapping(ranges: List<IntRange>): List<IntRange> {
    if (ranges.isEmpty()) return ranges
    val merged = mutableListOf(ranges.first())
    for (range in ranges.drop(1)) {
        val last = merged.last()
        if (range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/**
 * The Review queue's main card: header (merchant/amount/date/account badge),
 * a color-coded confidence chip, a collapsible raw-SMS inspector with
 * highlighted key terms, and the one-tap [CategorySuggestionRow] underneath.
 */
@Composable
fun TransactionDetailReviewCard(
    transaction: Transaction,
    suggestions: List<CategorySuggestion>,
    confidenceLevel: ConfidenceLevel,
    accountLabel: String?,
    onSelectSuggestion: (CategorySuggestion) -> Unit,
    onMoreCategories: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clears a pending AI suggestion without assigning a category; shown only while one is pending. */
    onDismissSuggestion: (() -> Unit)? = null,
) {
    var smsExpanded by remember { mutableStateOf(false) }
    val amountDisplay = (if (transaction.direction == TransactionDirection.CREDIT) "+" else "-") +
        moneyLabel(transaction.amount, transaction.currencyCode)

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        transaction.merchant ?: transaction.extractedMerchant ?: transaction.sender ?: "Unknown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        transaction.timestampMillis.asDateTime(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    amountDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfidenceChip(confidenceLevel, transaction.aiConfidence)
                Text(
                    accountLabel ?: "Unassigned",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            transaction.rawMessage?.takeIf { it.isNotBlank() }?.let { raw ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { smsExpanded = !smsExpanded }
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Raw message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (smsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (smsExpanded) "Collapse raw message" else "Expand raw message",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = smsExpanded) {
                    val highlightBackground = MMTheme.colors.warning.copy(alpha = HIGHLIGHT_ALPHA)
                    Text(
                        highlightedSmsText(
                            raw,
                            transaction.amount.asQuantity(),
                            transaction.merchant,
                            highlightBackground,
                            MMTheme.colors.textPrimary,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            CategorySuggestionRow(
                suggestions = suggestions,
                onSelect = onSelectSuggestion,
                onMoreCategories = onMoreCategories,
            )
            if (onDismissSuggestion != null && transaction.needsCategoryApproval) {
                androidx.compose.material3.TextButton(onClick = onDismissSuggestion) {
                    Text("Dismiss suggestion")
                }
            }
        }
    }
}

@Composable
private fun ConfidenceChip(level: ConfidenceLevel, score: Int?) {
    val color = level.color(MMTheme.colors)
    Text(
        confidenceLabel(score),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun highlightedSmsText(
    raw: String,
    amount: String,
    merchant: String?,
    highlightBackground: Color,
    highlightText: Color,
) = buildAnnotatedString {
    append(raw)
    highlightRanges(raw, amount, merchant).forEach { range ->
        addStyle(
            SpanStyle(background = highlightBackground, color = highlightText, fontWeight = FontWeight.SemiBold),
            range.first,
            (range.last + 1).coerceAtMost(raw.length),
        )
    }
}
