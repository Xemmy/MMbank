package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The UAE Dirham symbol: a "D" spine and bowl crossed by two horizontal
 * strikes, drawn as a real vector so it stays crisp at any size instead of
 * an embedded raster. Sized entirely by [modifier] (e.g. `Modifier.size(12.dp)`)
 * — draws relative to the given box, same convention as [dottedLeader]/
 * [rowEdgeGlow]. Replaces the "AED" text label in [com.xemmy.mmbank.ui.util.AmountText].
 */
@Composable
fun AedGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Bolder than a typical text stroke on purpose: at the ~13dp size this
        // renders next to the numerals, thin strokes anti-alias into mush.
        val strokeWidth = minOf(w, h) * 0.13f
        val thinStrokeWidth = strokeWidth * 0.85f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        val spineX = w * 0.37f
        val topY = h * 0.11f
        val bottomY = h * 0.89f

        // Spine: the D's vertical stroke.
        drawLine(color, Offset(spineX, topY), Offset(spineX, bottomY), strokeWidth, StrokeCap.Round)

        // Bowl: the right half of an oval centered on the spine, open to the left.
        val rx = w * 0.27f
        val ry = h * 0.39f
        val bowlCenterY = h * 0.5f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(spineX - rx, bowlCenterY - ry),
            size = Size(rx * 2, ry * 2),
            style = stroke,
        )

        // Two horizontal strikes crossing the spine into the bowl, the mark
        // that reads this as a currency symbol rather than a bare letter.
        val strikeStartX = w * 0.19f
        val strikeEndX = spineX + rx * 0.80f
        val strikeY1 = h * 0.39f
        val strikeY2 = h * 0.61f
        drawLine(color, Offset(strikeStartX, strikeY1), Offset(strikeEndX, strikeY1), thinStrokeWidth, StrokeCap.Round)
        drawLine(color, Offset(strikeStartX, strikeY2), Offset(strikeEndX, strikeY2), thinStrokeWidth, StrokeCap.Round)
    }
}
