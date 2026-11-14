package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMergeTest {

    private fun account(
        name: String = "Current",
        type: AccountType = AccountType.CHECKING,
        currency: String = "AED",
        bank: String? = null,
        number: String? = null,
        endings: String? = null,
        iban: String? = null,
        swift: String? = null,
        title: String? = null,
        notes: String? = null,
        opening: String = "0",
    ) = Account(
        id = 5, name = name, type = type, currencyCode = currency, bankName = bank, accountNumber = number,
        cardEndings = endings, iban = iban, swift = swift, accountTitle = title, notes = notes,
        openingBalance = BigDecimal(opening),
    )

    @Test
    fun `blank fields are filled from the draft`() {
        val result = AccountMerge.merge(account(), account(bank = "ADCB", iban = "AE07"))
        assertEquals("ADCB", result.account.bankName)
        assertEquals("AE07", result.account.iban)
        assertEquals(listOf("bank name", "IBAN"), result.filled)
        assertTrue(result.changed)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `a field that already has a value is never overwritten`() {
        val result = AccountMerge.merge(account(bank = "ADCB"), account(bank = "Mashreq"))
        assertEquals("ADCB", result.account.bankName)
        assertEquals(1, result.conflicts.size)
        assertEquals("bank name", result.conflicts.single().field)
        assertEquals("ADCB", result.conflicts.single().existing)
        assertEquals("Mashreq", result.conflicts.single().incoming)
        assertFalse(result.changed)
    }

    @Test
    fun `the same value in another case or spacing is not a conflict`() {
        val result = AccountMerge.merge(
            account(bank = "ADCB", iban = "AE070331234567890123456"),
            account(bank = "adcb", iban = "ae07 0331 2345 6789 0123 456"),
        )
        assertTrue(result.conflicts.isEmpty())
        assertFalse(result.changed)
    }

    @Test
    fun `a longer or shorter form of the same number is not a conflict`() {
        val result = AccountMerge.merge(account(number = "001234"), account(number = "1234"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals("001234", result.account.accountNumber)
        assertNull(result.account.cardEndings)
        assertFalse(result.changed)
    }

    @Test
    fun `new card endings are appended to the existing ones`() {
        val result = AccountMerge.merge(
            account(type = AccountType.CREDIT_CARD, endings = "4543"),
            account(type = AccountType.CREDIT_CARD, endings = "1111, 4543"),
        )
        assertEquals("4543, 1111", result.account.cardEndings)
        assertEquals(listOf("card ending ••1111"), result.filled)
    }

    @Test
    fun `a different main number becomes an extra card ending`() {
        val result = AccountMerge.merge(account(number = "5555"), account(number = "7777"))
        assertEquals("5555", result.account.accountNumber)
        assertEquals("7777", result.account.cardEndings)
        assertEquals(listOf("card ending ••7777"), result.filled)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `a number only the draft has fills the main number`() {
        val result = AccountMerge.merge(account(), account(number = "7777"))
        assertEquals("7777", result.account.accountNumber)
        assertEquals(listOf("account number"), result.filled)
        assertNull(result.account.cardEndings)
    }

    @Test
    fun `type and currency are reported but never changed`() {
        val result = AccountMerge.merge(account(), account(type = AccountType.SAVINGS, currency = "USD"))
        assertEquals(AccountType.CHECKING, result.account.type)
        assertEquals("AED", result.account.currencyCode)
        assertEquals(setOf("type", "currency"), result.conflicts.map { it.field }.toSet())
    }

    @Test
    fun `name balance and archive state are left alone`() {
        val existing = account(name = "Keep me", opening = "250").copy(archived = true)
        val result = AccountMerge.merge(existing, account(name = "Other", opening = "999", bank = "ADCB"))
        assertEquals("Keep me", result.account.name)
        assertEquals(0, BigDecimal("250").compareTo(result.account.openingBalance))
        assertTrue(result.account.archived)
        assertEquals(5L, result.account.id)
    }

    @Test
    fun `a draft with nothing new changes nothing`() {
        val existing = account(bank = "ADCB", number = "1234")
        val result = AccountMerge.merge(existing, account())
        assertEquals(existing, result.account)
        assertFalse(result.changed)
    }
}
