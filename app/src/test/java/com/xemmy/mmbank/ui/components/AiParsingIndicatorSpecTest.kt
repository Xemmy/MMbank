package com.xemmy.mmbank.ui.components

import com.xemmy.mmbank.domain.model.DesignMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fluid AGSL shader needs RuntimeShader (API 33+) and is only worth the
 * cost in Premium mode -- every other combination must fall back to the
 * plain spinner, never crash by reaching for RuntimeShader on an older API.
 */
class AiParsingIndicatorSpecTest {

    @Test
    fun `premium mode on API 33+ uses the shader`() {
        assertTrue(AiParsingIndicatorSpec.usesShader(DesignMode.PREMIUM, 33))
        assertTrue(AiParsingIndicatorSpec.usesShader(DesignMode.PREMIUM, 35))
    }

    @Test
    fun `premium mode below API 33 falls back to the spinner`() {
        assertFalse(AiParsingIndicatorSpec.usesShader(DesignMode.PREMIUM, 26))
        assertFalse(AiParsingIndicatorSpec.usesShader(DesignMode.PREMIUM, 32))
    }

    @Test
    fun `classic mode never uses the shader, regardless of API level`() {
        assertFalse(AiParsingIndicatorSpec.usesShader(DesignMode.CLASSIC, 33))
        assertFalse(AiParsingIndicatorSpec.usesShader(DesignMode.CLASSIC, 35))
    }
}
