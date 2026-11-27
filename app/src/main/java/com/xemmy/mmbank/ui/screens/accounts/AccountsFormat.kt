package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal

/**
 * An account balance is signed, but MoneyText takes a magnitude plus a kind.
 * Negative balances (money owed) render with a minus sign via SPEND; zero and
 * positive balances render unsigned via TRANSFER (the neutral, no-sign kind).
 */
fun balanceKind(balance: BigDecimal): TransactionKind =
    if (balance.signum() < 0) TransactionKind.SPEND else TransactionKind.TRANSFER

/** The unsigned amount handed to MoneyText alongside [balanceKind]. */
fun balanceMagnitude(balance: BigDecimal): Double = balance.abs().toDouble()
