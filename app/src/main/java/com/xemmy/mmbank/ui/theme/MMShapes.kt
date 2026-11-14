package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (Improvement Plan §1.3). Screen gutter is 16, card padding 16, card gap 12, section gap 24. */
object MMSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val gutter: Dp = lg
    val cardPadding: Dp = lg
    val cardGap: Dp = md
    val sectionGap: Dp = xl
}

private fun corner(size: Dp) = RoundedCornerShape(CornerSize(size))

/** extraSmall 8 tags · small 12 chips/fields · medium 16 cards/rows · large 24 dialogs · extraLarge 28 sheets. */
val MMShapes = Shapes(
    extraSmall = corner(8.dp),
    small = corner(12.dp),
    medium = corner(16.dp),
    large = corner(24.dp),
    extraLarge = corner(28.dp),
)
