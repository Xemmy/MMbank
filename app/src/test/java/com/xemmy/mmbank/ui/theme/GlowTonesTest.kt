package com.xemmy.mmbank.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The contextual glow is semantic: direction picks mint/copper, account origin
 * picks a brand tint. Selection must be case-insensitive and work off any of
 * the labels an account might be known by.
 */
class GlowTonesTest {

    @Test
    fun `credit glows mint, debit glows copper`() {
        assertEquals(GlowTone.CREDIT_MINT, GlowTones.forDirection(isCredit = true))
        assertEquals(GlowTone.DEBIT_COPPER, GlowTones.forDirection(isCredit = false))
    }

    @Test
    fun `binance mastercard account name maps to gold`() {
        assertEquals(GlowTone.BINANCE_GOLD, GlowTones.forAccountOrigin("Binance Mastercard"))
    }

    @Test
    fun `tabby maps to teal regardless of case`() {
        assertEquals(GlowTone.TABBY_TEAL, GlowTones.forAccountOrigin("TABBY Card"))
        assertEquals(GlowTone.TABBY_TEAL, GlowTones.forAccountOrigin("tabby"))
    }

    @Test
    fun `any label position matches - bank name or sender, nulls skipped`() {
        assertEquals(
            GlowTone.BINANCE_GOLD,
            GlowTones.forAccountOrigin(null, "Binance", null),
        )
        assertEquals(
            GlowTone.TABBY_TEAL,
            GlowTones.forAccountOrigin("My BNPL card", null, "com.tabby.customer"),
        )
    }

    @Test
    fun `unbranded banks glow nothing`() {
        assertNull(GlowTones.forAccountOrigin("ADCB Current", "ADCB"))
        assertNull(GlowTones.forAccountOrigin(null, null))
        assertNull(GlowTones.forAccountOrigin())
    }

    @Test
    fun `each brand tone carries its own edge-glow alpha per the Obsidian spec`() {
        assertEquals(0.28f, GlowAlphas.origin(GlowTone.BINANCE_GOLD), 0.001f)
        assertEquals(0.26f, GlowAlphas.origin(GlowTone.TABBY_TEAL), 0.001f)
        assertEquals(0.18f, GlowAlphas.origin(GlowTone.SYSTEM_SLATE), 0.001f)
    }

    @Test
    fun `direction tones fall back to the direction alpha`() {
        assertEquals(GlowAlphas.DIRECTION, GlowAlphas.origin(GlowTone.CREDIT_MINT), 0.001f)
        assertEquals(GlowAlphas.DIRECTION, GlowAlphas.origin(GlowTone.DEBIT_COPPER), 0.001f)
    }
}
