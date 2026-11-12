package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.ui.theme.darkMMColors
import com.xemmy.mmbank.ui.theme.lightMMColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToneTest {
    @Test
    fun `tones map to the info warning and danger tokens in both themes`() {
        for (c in listOf(darkMMColors(), lightMMColors())) {
            assertEquals(c.info, c.toneColor(MMTone.INFO))
            assertEquals(c.warning, c.toneColor(MMTone.WARNING))
            assertEquals(c.danger, c.toneColor(MMTone.DANGER))
            assertNotEquals(c.toneColor(MMTone.INFO), c.toneColor(MMTone.DANGER))
        }
    }

    @Test
    fun `every tone has a distinct spoken label`() {
        val labels = MMTone.entries.map(::toneLabel)
        assertEquals(labels.size, labels.toSet().size)
    }
}
