package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dirham glyph's SVG path data is hand-written; a typo would otherwise
 * only surface as a runtime parse failure on first composition. Parsing it
 * here catches that at test time instead.
 */
class CurrencyGlyphsTest {

    @Test
    fun `dirham path data parses into vector nodes`() {
        assertTrue(addPathNodes(DIRHAM_D_PATH).isNotEmpty())
        assertTrue(addPathNodes(DIRHAM_BARS_PATH).isNotEmpty())
    }

    @Test
    fun `letterform has outline plus counter, and exactly two strike bars`() {
        // Two closed subpaths in the D (outer contour + even-odd counter hole).
        assertEquals(2, addPathNodes(DIRHAM_D_PATH).count { it is PathNode.Close })
        // Two closed rectangles for the strikes.
        assertEquals(2, addPathNodes(DIRHAM_BARS_PATH).count { it is PathNode.Close })
    }
}
