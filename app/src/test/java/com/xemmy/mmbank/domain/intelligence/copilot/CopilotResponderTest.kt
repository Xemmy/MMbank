package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.model.Category
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotResponderTest {

    private val today = LocalDate.of(2026, 11, 7)
    private val categories = listOf(Category(id = 2, name = "Dining", icon = "", colorHex = "#000000"))

    private fun decide(q: String, ai: Boolean) = CopilotResponder.decide(q, categories, listOf("Careem"), today, ai)

    @Test
    fun `an understood question is answered on the phone even with AI set up`() {
        val d = decide("how much on dining last month", ai = true)
        assertTrue(d is CopilotDecision.Local)
        assertEquals(CopilotIntent.SPEND, (d as CopilotDecision.Local).query.intent)
    }

    @Test
    fun `an open question goes to the AI with the range it named`() {
        val d = decide("why was october so expensive", ai = true) as CopilotDecision.AskAi
        assertEquals(LocalDate.of(2026, 10, 1), d.range.window.start)
        assertEquals(LocalDate.of(2026, 10, 31), d.range.window.end)
    }

    @Test
    fun `an open question with no range sends this month`() {
        val d = decide("any tips for me", ai = true) as CopilotDecision.AskAi
        assertEquals("this month", d.range.label)
    }

    @Test
    fun `without AI an open question gets help`() {
        assertEquals(CopilotDecision.Help, decide("any tips for me", ai = false))
        assertTrue(CopilotResponder.helpText(aiConfigured = false).contains("Settings"))
        assertFalse(CopilotResponder.helpText(aiConfigured = true).contains("Settings"))
    }
}
