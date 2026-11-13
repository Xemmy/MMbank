package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.account.AccountUpsertResolver.MatchedOn
import com.xemmy.mmbank.domain.account.AccountUpsertResolver.Result
import com.xemmy.mmbank.domain.account.AccountUpsertResolver.Strength
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddPlannerTest {

    private fun account(
        id: Long,
        name: String,
        type: AccountType = AccountType.CHECKING,
        number: String? = null,
        endings: String? = null,
        iban: String? = null,
        opening: String = "0",
        archived: Boolean = false,
    ) = Account(
        id = id, name = name, type = type, currencyCode = "AED", accountNumber = number, cardEndings = endings,
        iban = iban, openingBalance = BigDecimal(opening), archived = archived,
    )

    private fun input(
        name: String = "ADCB",
        type: AccountType = AccountType.CHECKING,
        balance: String = "",
        digits: String = "",
    ) = QuickAddInput(type = type, name = name, currency = "AED", balance = balance, lastDigits = digits)

    @Test
    fun `a blank name is invalid`() {
        val plan = QuickAddPlanner.plan(input(name = " "), emptyList(), emptyMap())
        assertTrue(plan is QuickAddPlan.Invalid)
        assertTrue((plan as QuickAddPlan.Invalid).errors.containsKey(AccountField.NAME))
    }

    @Test
    fun `an unreadable balance is invalid`() {
        val plan = QuickAddPlanner.plan(input(balance = "lots"), emptyList(), emptyMap())
        assertTrue((plan as QuickAddPlan.Invalid).errors.containsKey(AccountField.OPENING))
    }

    @Test
    fun `something new is created with the typed balance as its opening balance`() {
        val plan = QuickAddPlanner.plan(input(balance = "1,250.50", digits = "1234"), emptyList(), emptyMap())
        assertTrue(plan is QuickAddPlan.Create)
        val created = (plan as QuickAddPlan.Create).account
        assertEquals("ADCB", created.name)
        assertEquals("AED", created.currencyCode)
        assertEquals(0, BigDecimal("1250.50").compareTo(created.openingBalance))
        assertEquals("1234", created.accountNumber)
        assertEquals(0L, created.id)
    }

    @Test
    fun `a blank balance creates with zero`() {
        val created = (QuickAddPlanner.plan(input(), emptyList(), emptyMap()) as QuickAddPlan.Create).account
        assertEquals(0, BigDecimal.ZERO.compareTo(created.openingBalance))
    }

    @Test
    fun `the same last digits update the existing account and report the balance difference`() {
        val existing = account(7, "ADCB Current", number = "001234", opening = "100")
        val plan = QuickAddPlanner.plan(input(balance = "800", digits = "1234"), listOf(existing), mapOf(7L to BigDecimal("500")))
        assertTrue(plan is QuickAddPlan.Update)
        plan as QuickAddPlan.Update
        assertEquals(7L, plan.existing.id)
        assertEquals(MatchedOn.NUMBER, plan.matchedOn)
        assertEquals(Strength.STRONG, plan.strength)
        assertEquals(0, BigDecimal("500").compareTo(plan.currentBalance))
        assertEquals(0, BigDecimal("800").compareTo(plan.enteredBalance))
        assertTrue(plan.balanceDiffers)
        assertEquals("Update balance", QuickAddPlanner.primaryLabel(plan))
    }

    @Test
    fun `an entered balance equal to the computed one needs no adjustment`() {
        val existing = account(7, "ADCB Current", number = "1234")
        val plan = QuickAddPlanner.plan(input(balance = "500.00", digits = "1234"), listOf(existing), mapOf(7L to BigDecimal("500"))) as QuickAddPlan.Update
        assertFalse(plan.balanceDiffers)
        assertEquals("Got it", QuickAddPlanner.primaryLabel(plan))
    }

    @Test
    fun `no balance typed means no adjustment`() {
        val existing = account(7, "ADCB Current", number = "1234")
        val plan = QuickAddPlanner.plan(input(digits = "1234"), listOf(existing), mapOf(7L to BigDecimal("500"))) as QuickAddPlan.Update
        assertNull(plan.enteredBalance)
        assertFalse(plan.balanceDiffers)
    }

    @Test
    fun `an archived match is restored when updated`() {
        val closed = account(7, "Old", number = "1234", archived = true)
        val plan = QuickAddPlanner.plan(input(digits = "1234"), listOf(closed), emptyMap()) as QuickAddPlan.Update
        assertTrue(plan.archived)
        assertEquals("Restore and update", QuickAddPlanner.primaryLabel(plan))
    }

    @Test
    fun `details the account lacked are offered as an update`() {
        val existing = account(7, "ADCB Current", number = "1234")
        val typed = input(digits = "1234").copy(bankName = "ADCB")
        val plan = QuickAddPlanner.plan(typed, listOf(existing), emptyMap()) as QuickAddPlan.Update
        assertEquals(listOf("bank name"), plan.merge.filled)
        assertEquals("Add details", QuickAddPlanner.primaryLabel(plan))
    }

    @Test
    fun `a name-only match is weak and falls back to the opening balance`() {
        val cash = account(3, "Cash", type = AccountType.CASH, opening = "40")
        val plan = QuickAddPlanner.plan(input(name = "cash", type = AccountType.CASH, balance = "55"), listOf(cash), emptyMap()) as QuickAddPlan.Update
        assertEquals(Strength.WEAK, plan.strength)
        assertEquals(MatchedOn.NAME, plan.matchedOn)
        assertEquals(0, BigDecimal("40").compareTo(plan.currentBalance))
        assertTrue(plan.balanceDiffers)
        assertEquals("cash", plan.draft.name)
    }

    @Test
    fun `numbers that point at two accounts ask which one`() {
        val a = account(1, "A", endings = "1111")
        val b = account(2, "B", endings = "2222")
        val typed = input(digits = "1111").copy(accountNumber = "2222")
        val plan = QuickAddPlanner.plan(typed, listOf(a, b), emptyMap())
        assertTrue(plan is QuickAddPlan.Choose)
        assertEquals(setOf(1L, 2L), (plan as QuickAddPlan.Choose).accounts.map { it.id }.toSet())
    }

    @Test
    fun `a picked account becomes an update`() {
        val a = account(1, "A", opening = "10")
        val choose = QuickAddPlan.Choose(listOf(a), MatchedOn.NUMBER, a.copy(id = 0, name = "Typed"))
        val update = QuickAddPlanner.updateOf(a, choose.matchedOn, choose.draft, BigDecimal("30"), emptyMap())
        assertEquals(1L, update.existing.id)
        assertEquals(Strength.STRONG, update.strength)
        assertTrue(update.balanceDiffers)
    }

    @Test
    fun `the summary says what will happen`() {
        val existing = account(7, "ADCB Current", number = "1234")
        val typed = input(balance = "800", digits = "1234").copy(bankName = "ADCB")
        val plan = QuickAddPlanner.plan(typed, listOf(existing), mapOf(7L to BigDecimal("500"))) as QuickAddPlan.Update
        val lines = QuickAddPlanner.summary(plan) { it.setScale(0, RoundingMode.HALF_UP).toPlainString() }
        assertEquals("Matched on account or card number.", lines[0])
        assertTrue(lines.any { it.startsWith("Balance now 500; you entered 800.") })
        assertTrue(lines.any { it == "Adds: bank name." })
    }

    @Test
    fun `the summary reports what was kept`() {
        val existing = account(7, "ADCB Current", number = "1234").copy(bankName = "ADCB")
        val typed = input(digits = "1234").copy(bankName = "Mashreq")
        val plan = QuickAddPlanner.plan(typed, listOf(existing), emptyMap()) as QuickAddPlan.Update
        val lines = QuickAddPlanner.summary(plan) { it.setScale(0, RoundingMode.HALF_UP).toPlainString() }
        assertTrue(lines.any { it == "Kept as they were (different from what you typed): bank name." })
    }

    @Test
    fun `the full-form draft keeps everything typed even when invalid`() {
        val typed = QuickAddInput(type = AccountType.CREDIT_CARD, name = " Visa ", currency = "", balance = "-300", lastDigits = "4543", bankName = "ADCB")
        val draft = QuickAddPlanner.draftForFullForm(typed, fallbackCurrency = "AED")
        assertEquals("Visa", draft.name)
        assertEquals("AED", draft.currencyCode)
        assertEquals(0, BigDecimal("-300").compareTo(draft.openingBalance))
        assertEquals("4543", draft.accountNumber)
        assertEquals("ADCB", draft.bankName)
        assertEquals(0L, draft.id)
    }

    @Test
    fun `guard lets a unique new account through`() {
        assertNull(QuickAddPlanner.guardFullForm(account(0, "Fresh", number = "9999"), listOf(account(1, "Other", number = "1234"))))
    }

    @Test
    fun `guard stops a new account that repeats an iban`() {
        val existing = account(1, "Current", iban = "AE070331234567890123456")
        val result = QuickAddPlanner.guardFullForm(account(0, "Copy", iban = "AE07 0331 2345 6789 0123 456"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.IBAN, Strength.STRONG), result)
    }

    @Test
    fun `guard stops a new account that only repeats a name`() {
        val existing = account(1, "Main")
        val result = QuickAddPlanner.guardFullForm(account(0, "Main"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.NAME, Strength.WEAK), result)
    }

    @Test
    fun `guard does not nag an edit about a lookalike name`() {
        val self = account(1, "Main")
        val other = account(2, "Main")
        assertNull(QuickAddPlanner.guardFullForm(self, listOf(self, other)))
    }

    @Test
    fun `guard stops an edit that collides on a number`() {
        val self = account(1, "Current", number = "1234")
        val other = account(2, "Savings", type = AccountType.SAVINGS, number = "1234")
        val result = QuickAddPlanner.guardFullForm(self, listOf(self, other))
        assertEquals(Result.Existing(other, MatchedOn.NUMBER, Strength.STRONG), result)
    }

    @Test
    fun `guard leaves an unchanged edit alone`() {
        val self = account(1, "Current", number = "1234", iban = "AE070331234567890123456")
        assertNull(QuickAddPlanner.guardFullForm(self, listOf(self)))
    }
}
