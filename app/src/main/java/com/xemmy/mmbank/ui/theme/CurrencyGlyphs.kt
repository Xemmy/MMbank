package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * SVG path data for the official UAE Dirham symbol (the "D" with two strike
 * bars), hand-crafted as a filled letterform: outer contour + counter hole
 * (even-odd) plus two bars protruding left of the stem. Tight 20x24 viewport.
 *
 * Single source of truth shared verbatim with the standalone assets in
 * design/aed-dirham*.svg and design/AedDirhamIcon.tsx — keep them in sync.
 */
internal const val DIRHAM_D_PATH =
    "M3.4 0.4 H9 C14.85 0.4 19.6 5.59 19.6 12 C19.6 18.41 14.85 23.6 9 23.6 H3.4 Z " +
        "M6 3 H9 C13.42 3 17 7.03 17 12 C17 16.97 13.42 21 9 21 H6 Z"
internal const val DIRHAM_BARS_PATH =
    "M0 7.55 H16.1 V9.75 H0 Z M0 14.25 H16.1 V16.45 H0 Z"

/**
 * The Dirham symbol as a tintable vector. Built with a white fill so
 * [Icon]'s tint (or any ColorFilter) recolors it — the Compose analog of an
 * SVG using currentColor.
 */
val DirhamSymbol: ImageVector by lazy {
    ImageVector.Builder(
        name = "DirhamSymbol",
        defaultWidth = 20.dp,
        defaultHeight = 24.dp,
        viewportWidth = 20f,
        viewportHeight = 24f,
    )
        .addPath(
            pathData = addPathNodes(DIRHAM_D_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.White),
        )
        .addPath(
            pathData = addPathNodes(DIRHAM_BARS_PATH),
            fill = SolidColor(Color.White),
        )
        .build()
}

/**
 * The Dirham symbol tinted to [color]. [com.xemmy.mmbank.ui.util.AmountText]
 * embeds this as inline text content at digit cap height, baseline-aligned,
 * so AED amounts read like the banking apps' "Đ46,122.24". Content
 * description stays null: the surrounding inline content already carries a
 * "Đ" alternate for accessibility, and a second announcement would stutter.
 */
@Composable
fun AedGlyph(color: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = DirhamSymbol,
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}
