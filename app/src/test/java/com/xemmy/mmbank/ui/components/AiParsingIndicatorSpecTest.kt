package com.xemmy.mmbank.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fluid AGSL shader needs RuntimeShader (API 33+) and is skipped when the
 * user turned animations off -- every other combination must fall back to the
 * plain spinner, never crash by reaching for RuntimeShader on an older API.
 */
class AiParsingIndicatorSpecTest {

    @Test
    fun `API 33+ with animations on uses the shader`() {
        assertTrue(AiParsingIndicatorSpec.usesShader(33, reduceMotion = false))
        assertTrue(AiParsingIndicatorSpec.usesShader(35, reduceMotion = false))
    }

    @Test
    fun `below API 33 falls back to the spinner`() {
        assertFalse(AiParsingIndicatorSpec.usesShader(26, reduceMotion = false))
        assertFalse(AiParsingIndicatorSpec.usesShader(32, reduceMotion = false))
    }

    @Test
    fun `reduce motion never uses the shader, regardless of API level`() {
        assertFalse(AiParsingIndicatorSpec.usesShader(33, reduceMotion = true))
        assertFalse(AiParsingIndicatorSpec.usesShader(35, reduceMotion = true))
    }
}
