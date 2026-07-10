package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Initials shown on a [RowAvatar]: first letter of the first two words, or
 * the first two letters of a single word. Pure string logic — JVM-testable,
 * independent of the Compose rendering.
 */
fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Row avatar (spec 5.3): a [ObsidianTokens.surfaceGlass] tile with brand-tinted
 * Space Grotesk initials — gold for Binance, teal for Tabby, slate for a
 * system/manual account with no registered brand.
 */
@Composable
fun RowAvatar(
    name: String,
    tone: GlowTone,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(ObsidianTokens.avatarSize)
            .clip(RoundedCornerShape(ObsidianTokens.avatarRadius))
            .background(ObsidianTokens.surfaceGlass),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarInitials(name),
            color = GlowPalette.color(tone),
            fontFamily = AppFonts.spaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/**
 * Dotted leader (spec 5.3): a faint dashed rule filling the gap between a
 * row's text block and its amount. Draw on a `Modifier.weight(1f)` spacer
 * with at least [minWidth] so it never fully disappears when text is long —
 * the merchant name ellipsizes first.
 */
fun Modifier.dottedLeader(
    color: Color = Color.White.copy(alpha = 0.20f),
    strokeWidth: Dp = 1.5.dp,
    dashLength: Dp = 3.dp,
    gapLength: Dp = 3.dp,
): Modifier = this.drawBehind {
    val y = size.height / 2f
    val dash = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f)
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidth.toPx(),
        pathEffect = dash,
        cap = StrokeCap.Round,
    )
}
