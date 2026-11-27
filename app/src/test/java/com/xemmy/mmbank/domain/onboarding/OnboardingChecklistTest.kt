package com.xemmy.mmbank.domain.onboarding

import com.xemmy.mmbank.domain.onboarding.OnboardingChecklist.Inputs
import com.xemmy.mmbank.domain.onboarding.OnboardingChecklist.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingChecklistTest {

    private fun build(capture: Boolean = false, accounts: Int = 0, txs: Int = 0, backup: Boolean = false) =
        OnboardingChecklist.build(Inputs(capture, accounts, txs, backup))

    @Test
    fun `a fresh install has four steps, none done, and starts with capture`() {
        val c = build()
        assertEquals(listOf(StepId.ALLOW_CAPTURE, StepId.ADD_ACCOUNT, StepId.FIRST_TRANSACTION, StepId.BACK_UP), c.steps.map { it.id })
        assertEquals(0, c.doneCount)
        assertFalse(c.complete)
        assertEquals(StepId.ALLOW_CAPTURE, c.next!!.id)
    }

    @Test
    fun `each step ticks itself off from real state`() {
        assertTrue(build(capture = true).steps.first { it.id == StepId.ALLOW_CAPTURE }.done)
        assertTrue(build(accounts = 1).steps.first { it.id == StepId.ADD_ACCOUNT }.done)
        assertTrue(build(txs = 5).steps.first { it.id == StepId.FIRST_TRANSACTION }.done)
        assertTrue(build(backup = true).steps.first { it.id == StepId.BACK_UP }.done)
    }

    @Test
    fun `next is the first unfinished step, wherever it is`() {
        assertEquals(StepId.ADD_ACCOUNT, build(capture = true).next!!.id)
        assertEquals(StepId.BACK_UP, build(capture = true, accounts = 2, txs = 9).next!!.id)
        assertEquals(StepId.ALLOW_CAPTURE, build(accounts = 2, txs = 9, backup = true).next!!.id)
    }

    @Test
    fun `complete when every step is done, and then there is no next`() {
        val c = build(capture = true, accounts = 1, txs = 1, backup = true)
        assertTrue(c.complete)
        assertEquals(4, c.doneCount)
        assertNull(c.next)
    }

    @Test
    fun `the card shows only while something is left and it has not been dismissed`() {
        assertTrue(OnboardingChecklist.shouldShow(dismissed = false, checklist = build()))
        assertFalse(OnboardingChecklist.shouldShow(dismissed = true, checklist = build()))
        assertFalse(OnboardingChecklist.shouldShow(dismissed = false, checklist = build(true, 1, 1, true)))
    }
}
