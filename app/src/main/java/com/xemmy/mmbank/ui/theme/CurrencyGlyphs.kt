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
 * strikes. Drawn as a vector so it stays crisp at any size, and fills its
 * whole bounding box — [com.xemmy.mmbank.ui.util.AmountText] embeds it as
 * inline text content sized in em, so it renders at digit cap height,
 * baseline-aligned, exactly like the symbol in banking apps ("Đ46,122.24").
 * Stroke weight is tuned to match IBM Plex Mono SemiBold stems so it reads
 * as part of the amount, not an icon beside it. The box wants a ~0.83
 * width:height aspect; other aspects just stretch proportionally.
 */
@Composable
fun AedGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = h * 0.14f
        val thinStrokeWidth = strokeWidth * 0.9f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)

        val spineX = w * 0.28f

        // Spine: the D's vertical stroke, running the full box height.
        drawLine(
            color,
            Offset(spineX, strokeWidth / 2),
            Offset(spineX, h - strokeWidth / 2),
            strokeWidth,
            StrokeCap.Butt,
        )

        // Bowl: the right half of an oval anchored on the spine.
        val rx = w * 0.60f
        val ry = h * 0.46f - strokeWidth / 2
        val cy = h * 0.5f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(spineX - rx, cy - ry),
            size = Size(rx * 2, ry * 2),
            style = stroke,
        )

        // Two horizontal strikes protruding left of the spine and reaching
        // into the bowl — the mark that makes this a currency symbol.
        val strikeStartX = w * 0.02f
        val strikeEndX = w * 0.70f
        for (fy in floatArrayOf(0.40f, 0.60f)) {
            drawLine(
                color,
                Offset(strikeStartX, h * fy),
                Offset(strikeEndX, h * fy),
                thinStrokeWidth,
                StrokeCap.Butt,
            )
        }
    }
}
