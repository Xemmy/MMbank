package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountUpsertResolver.MatchedOn
import com.xemmy.mmbank.domain.account.AccountUpsertResolver.Result
import com.xemmy.mmbank.domain.account.AccountUpsertResolver.Strength
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountUpsertResolverTest {

    private fun account(
        id: Long,
        name: String,
        type: AccountType = AccountType.CHECKING,
        currency: String = "AED",
        bank: String? = null,
        number: String? = null,
        endings: String? = null,
        iban: String? = null,
        archived: Boolean = false,
    ) = Account(
        id = id, name = name, type = type, currencyCode = currency, bankName = bank,
        accountNumber = number, cardEndings = endings, iban = iban, archived = archived,
    )

    private fun draft(
        name: String = "Something",
        type: AccountType = AccountType.CHECKING,
        currency: String = "AED",
        bank: String? = null,
        number: String? = null,
        endings: String? = null,
        iban: String? = null,
    ) = AccountUpsertResolver.Draft(type, name, currency, bank, number, endings, iban)

    @Test
    fun `nothing in common is new`() {
        val result = AccountUpsertResolver.resolve(draft(name = "Brand new"), listOf(account(1, "Other", number = "9999")))
        assertEquals(Result.New, result)
    }

    @Test
    fun `an empty list is new`() {
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(), emptyList()))
    }

    @Test
    fun `same iban matches whatever the spacing and case`() {
        val existing = account(1, "ADCB Current", iban = "AE070331234567890123456")
        val result = AccountUpsertResolver.resolve(draft(name = "New", iban = "ae07 0331 2345 6789 0123 456"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.IBAN, Strength.STRONG), result)
    }

    @Test
    fun `a shorter number matches the same account's longer one`() {
        val existing = account(1, "ADCB Current", number = "001234")
        val result = AccountUpsertResolver.resolve(draft(name = "New", number = "1234"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.NUMBER, Strength.STRONG), result)
    }

    @Test
    fun `a number matches an extra card ending`() {
        val existing = account(1, "Visa", type = AccountType.CREDIT_CARD, number = "7777", endings = "4543, 1111")
        val result = AccountUpsertResolver.resolve(draft(name = "New", type = AccountType.CREDIT_CARD, number = "4543"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.NUMBER, Strength.STRONG), result)
    }

    @Test
    fun `fewer than three digits is no evidence`() {
        val existing = account(1, "Current", number = "1234")
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(name = "New", number = "34"), listOf(existing)))
    }

    @Test
    fun `a tie between accounts on one number is not a match`() {
        val a = account(1, "A", number = "991234")
        val b = account(2, "B", number = "881234")
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(name = "New", number = "1234"), listOf(a, b)))
    }

    @Test
    fun `two numbers that point at different accounts are ambiguous`() {
        val a = account(1, "A", number = "1111")
        val b = account(2, "B", endings = "2222")
        val result = AccountUpsertResolver.resolve(draft(name = "New", number = "1111", endings = "2222"), listOf(a, b))
        assertTrue(result is Result.Ambiguous)
        result as Result.Ambiguous
        assertEquals(MatchedOn.NUMBER, result.matchedOn)
        assertEquals(setOf(1L, 2L), result.accounts.map { it.id }.toSet())
    }

    @Test
    fun `same name bank currency and type is only a weak match`() {
        val existing = account(1, "Main Account", bank = "ADCB")
        val result = AccountUpsertResolver.resolve(draft(name = "  main account ", bank = "adcb"), listOf(existing))
        assertEquals(Result.Existing(existing, MatchedOn.NAME, Strength.WEAK), result)
    }

    @Test
    fun `a different currency type or bank is not the same account`() {
        val existing = account(1, "Main", bank = "ADCB")
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(name = "Main", bank = "ADCB", currency = "USD"), listOf(existing)))
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(name = "Main", bank = "ADCB", type = AccountType.SAVINGS), listOf(existing)))
        assertEquals(Result.New, AccountUpsertResolver.resolve(draft(name = "Main", bank = "Mashreq"), listOf(existing)))
    }

    @Test
    fun `two lookalikes by name are ambiguous`() {
        val a = account(1, "Cash", type = AccountType.CASH)
        val b = account(2, "cash", type = AccountType.CASH)
        val result = AccountUpsertResolver.resolve(draft(name = "Cash", type = AccountType.CASH), listOf(a, b))
        assertTrue(result is Result.Ambiguous)
        assertEquals(MatchedOn.NAME, (result as Result.Ambiguous).matchedOn)
    }

    @Test
    fun `archived accounts are found too`() {
        val closed = account(1, "Old card", number = "5555", archived = true)
        val result = AccountUpsertResolver.resolve(draft(name = "New", number = "5555"), listOf(closed))
        assertEquals(Result.Existing(closed, MatchedOn.NUMBER, Strength.STRONG), result)
    }

    @Test
    fun `iban outranks a number that points elsewhere`() {
        val byIban = account(1, "A", iban = "AE070331234567890123456")
        val byNumber = account(2, "B", number = "1234")
        val result = AccountUpsertResolver.resolve(
            draft(name = "New", number = "1234", iban = "AE070331234567890123456"),
            listOf(byIban, byNumber),
        )
        assertEquals(Result.Existing(byIban, MatchedOn.IBAN, Strength.STRONG), result)
    }

    @Test
    fun `an account is never a duplicate of itself`() {
        val self = account(1, "Current", number = "1234", iban = "AE070331234567890123456")
        assertEquals(Result.New, AccountUpsertResolver.resolve(AccountUpsertResolver.draftOf(self), listOf(self), ignoreId = 1))
    }

    @Test
    fun `an edit that collides with another account is found`() {
        val self = account(1, "Current", number = "1234")
        val other = account(2, "Savings", type = AccountType.SAVINGS, number = "1234")
        val result = AccountUpsertResolver.resolve(AccountUpsertResolver.draftOf(self), listOf(self, other), ignoreId = 1)
        assertEquals(Result.Existing(other, MatchedOn.NUMBER, Strength.STRONG), result)
    }

    @Test
    fun `normalizeIban drops spaces and upper-cases`() {
        assertEquals("AE07", AccountUpsertResolver.normalizeIban(" ae 07 "))
        assertEquals(null, AccountUpsertResolver.normalizeIban("   "))
        assertEquals(null, AccountUpsertResolver.normalizeIban(null))
    }
}
