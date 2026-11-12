package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import java.math.BigDecimal

/** Net worth headline: the mask when hidden, otherwise the amount, prefixed "≈ " when some balances lack a rate. */
internal fun netWorthDisplay(netWorth: Double, currency: String, approximate: Boolean, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else (if (approximate) "≈ " else "") + moneyLabel(netWorth, currency)

/** Home rows only know credit vs debit, so credits read as income and debits as spending. */
internal fun homeRowKind(direction: TransactionDirection): TransactionKind =
    if (direction == TransactionDirection.CREDIT) TransactionKind.INCOME else TransactionKind.SPEND

/** A Binance USD value: the mask when hidden, an em dash when the asset has no USD price. */
internal fun usdValueLabel(valueUsd: BigDecimal?, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else valueUsd?.toDouble()?.let { moneyLabel(it, "USD") } ?: "—"

/** One entry per currency of money that sits in no account, e.g. "10.00 AED  5.00 USD". */
internal fun unassignedTotalsLabel(totals: Map<String, BigDecimal>, hidden: Boolean): String =
    totals.entries.joinToString("  ") { (code, total) -> moneyLabel(total, code, hidden) }
