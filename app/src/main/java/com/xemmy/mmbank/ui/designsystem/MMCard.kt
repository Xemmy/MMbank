package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/**
 * Card on the `surface` token with a 1dp hairline, medium shape and 16dp padding.
 * Pass [onClick] for the interactive variant (button role, clipped ripple) and
 * [tone] for the status variant (leading accent bar).
 */
@Composable
fun MMCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: MMTone? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MMTheme.colors
    val cardColors = CardDefaults.cardColors(
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    )
    val border = BorderStroke(1.dp, colors.borderSubtle)
    val body: @Composable ColumnScope.() -> Unit = {
        // No IntrinsicSize here: a card may hold a lazy list or other subcomposing
        // layout, which throws when asked for intrinsic measurements. The accent bar
        // is drawn behind the content instead of being sized against it.
        val accent = tone?.let { colors.toneColor(it) }
        val barWidth = if (tone != null) MMSpacing.xs else 0.dp
        Column(
            Modifier
                .then(
                    if (accent != null) {
                        Modifier.drawBehind { drawRect(accent, size = Size(barWidth.toPx(), size.height)) }
                    } else {
                        Modifier
                    },
                )
                .padding(start = barWidth)
                .padding(MMSpacing.cardPadding),
            content = content,
        )
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = cardColors,
            border = border,
            content = body,
        )
    } else {
        Card(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = cardColors,
            border = border,
            content = body,
        )
    }
}
