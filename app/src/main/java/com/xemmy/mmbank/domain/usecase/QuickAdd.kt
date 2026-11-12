package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import java.math.BigDecimal

/**
 * Turns what the quick-add sheet collected -- an amount and an optional
 * merchant -- into a cash spend (backlog B.5 #1), or says why it can't. The
 * sheet opens from a launcher shortcut or a Quick Settings tile so a coffee
 * paid in cash is two taps and a number, not a trip through the app.
 *
 * The row lands on the user's cash account when there is one (preferring one
 * in the same currency), and unassigned otherwise, where the Unassigned
 * warning will surface it. It is an ordinary manual SPEND: visible in
 * Activity, editable and deletable like any other.
 */
object QuickAdd {

    sealed interface Result {
        data class Ok(val transaction: Transaction) : Result
        data class Invalid(val reason: String) : Result
    }

    const val MAX_AMOUNT = 10_000_000L
    const val MAX_MERCHANT_CHARS = 60
    const val RULE_LABEL = "Quick add"

    fun build(
        amountText: String,
        merchantText: String,
        currency: String,
        accounts: List<Account>,
        nowMillis: Long,
    ): Result {
        val amount = parseAmount(amountText)
            ?: return Result.Invalid("Enter an amount, like 12.50")
        if (amount.signum() <= 0) return Result.Invalid("The amount must be more than zero")
        if (amount.scale() > 2) return Result.Invalid("Use at most 2 decimals")
        if (amount > BigDecimal(MAX_AMOUNT)) return Result.Invalid("That amount is too large")

        val code = currency.trim().uppercase().ifEmpty { return Result.Invalid("No currency is set") }
        val merchant = merchantText.trim().take(MAX_MERCHANT_CHARS).takeIf { it.isNotBlank() }
        val cash = accounts.filter { it.type == AccountType.CASH && !it.archived }
        val account = cash.firstOrNull { it.currencyCode.equals(code, ignoreCase = true) } ?: cash.firstOrNull()

        return Result.Ok(
            Transaction(
                amount = amount.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it },
                currencyCode = code,
                direction = TransactionDirection.DEBIT,
                merchant = merchant,
                source = TransactionSource.MANUAL,
                timestampMillis = nowMillis,
                accountId = account?.id,
                matchedRuleName = RULE_LABEL,
                spendingCategory = MerchantCategorizer.categorize(merchant),
                kind = TransactionKind.SPEND,
                // The user entered it, so automation never second-guesses it.
                kindUserSet = true,
            ),
        )
    }

    /** "12.5", "1,250.00" and "12,5" (a decimal comma) all read as numbers; anything else is null. */
    internal fun parseAmount(text: String): BigDecimal? {
        var s = text.trim().replace(" ", "")
        if (s.isEmpty()) return null
        // A lone comma followed by 1-2 digits is a decimal comma; otherwise commas are thousands separators.
        s = if (Regex("^\\d+,\\d{1,2}$").matches(s)) s.replace(',', '.') else s.replace(",", "")
        return s.takeIf { Regex("^\\d+(\\.\\d+)?$").matches(it) }?.toBigDecimalOrNull()
    }
}
