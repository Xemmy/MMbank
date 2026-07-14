package com.xemmy.mmbank.ui.theme

import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Transactions screen tints a card's border with the actively filtered
 * account's brand tone, composing [TransactionFilterEngine.activeAccount] with
 * [GlowTones.forAccountOrigin] rather than a second, independently maintained
 * color map. This exercises that exact composition.
 */
class GlowToneResolutionTest {

    private val binanceAccount = Account(id = 1, name = "Binance Mastercard", type = AccountType.CREDIT_CARD, currencyCode = "USD")
    private val tabbyAccount = Account(id = 2, name = "Tabby Card", type = AccountType.CREDIT_CARD, currencyCode = "AED")
    private val plainAccount = Account(id = 3, name = "ADCB Current", type = AccountType.CHECKING, currencyCode = "AED", bankName = "ADCB")
    private val accounts = listOf(binanceAccount, tabbyAccount, plainAccount)

    private fun resolveTone(filter: AccountFilter) =
        TransactionFilterEngine.activeAccount(filter, accounts)
            ?.let { GlowTones.forAccountOrigin(it.name, it.bankName) }

    @Test
    fun `filtering to the Binance account resolves to the gold tone`() {
        assertEquals(GlowTone.BINANCE_GOLD, resolveTone(AccountFilter.Specific(binanceAccount.id)))
    }

    @Test
    fun `filtering to the Tabby account resolves to the teal tone`() {
        assertEquals(GlowTone.TABBY_TEAL, resolveTone(AccountFilter.Specific(tabbyAccount.id)))
    }

    @Test
    fun `filtering to an unbranded account resolves to no tone`() {
        assertNull(resolveTone(AccountFilter.Specific(plainAccount.id)))
    }

    @Test
    fun `no account filter resolves to no tone`() {
        assertNull(resolveTone(AccountFilter.Any))
        assertNull(resolveTone(AccountFilter.Unassigned))
    }
}
