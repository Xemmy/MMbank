package com.xemmy.mmbank.ui.screens.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedVisibleHeightTest {
    @Test fun fullyExpanded() = assertEquals(100, collapsedVisibleHeight(100, 0f))
    @Test fun fullyCollapsed() = assertEquals(0, collapsedVisibleHeight(100, 1f))
    @Test fun overshootNeverNegative() = assertEquals(0, collapsedVisibleHeight(100, 1.4f))
    @Test fun negativeFractionClamped() = assertEquals(100, collapsedVisibleHeight(100, -0.5f))
    @Test fun nanIsExpanded() = assertEquals(100, collapsedVisibleHeight(100, Float.NaN))
    @Test fun negativeHeightIsZero() = assertEquals(0, collapsedVisibleHeight(-38, 0f))
}
