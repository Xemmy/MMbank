package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal

/**
 * Builds both sides of a transfer the user enters by hand -- "move 2,000 from
 * Current to Savings", "pay 3,600 from Current to my ADCB card" -- so it lands
 * as one linked pair instead of two loose rows the user had to remember to
 * exclude. Pure, so the leg shapes are unit tested.
 *
 * A side may be null ("money in from outside", "money out to somewhere not
 * tracked"); at least one must be an account. Different currencies are
 * supported: [Draft.toAmount] is what arrived on the receiving side.
 */
object TransferLegs {

    data class Endpoint(val accountId: Long, val name: String, val type: AccountType, val currencyCode: String)

    data class Draft(
        val from: Endpoint?,
        val to: Endpoint?,
        val amount: BigDecimal,
        /** Amount that landed on [to]; null = same as [amount] (same currency). */
        val toAmount: BigDecimal? = null,
        val timestampMillis: Long,
        val note: String? = null,
        val person: String? = null,
        /** Who the missing side is; only meaningful when [from] or [to] is null. */
        val outside: OutsideParty = OutsideParty.MY_OTHER_ACCOUNT,
    )

    /**
     * "Whose account is it?" for a one-sided move. Money to or from **your own**
     * account that isn't tracked is still a transfer (neither spent nor earned);
     * money to or from **someone else** really leaves or enters your net worth, so it
     * is recorded as Spent / Received (with the person set) instead of vanishing from both.
     */
    enum class OutsideParty { MY_OTHER_ACCOUNT, SOMEONE_ELSE }

    /** Why [build] refuses a draft, or null when it's valid. */
    fun validate(draft: Draft): String? = when {
        draft.from == null && draft.to == null -> "Pick at least one account"
        draft.from != null && draft.from.accountId == draft.to?.accountId -> "From and To must be different accounts"
        draft.amount.signum() <= 0 -> "Amount must be positive"
        draft.toAmount != null && draft.toAmount.signum() <= 0 -> "Received amount must be positive"
        else -> null
    }

    /** Paying anything onto a credit card is a card payment; everything else is a transfer. */
    fun kindFor(draft: Draft): TransactionKind =
        if (draft.to?.type == AccountType.CREDIT_CARD || draft.from?.type == AccountType.CREDIT_CARD) {
            TransactionKind.CARD_PAYMENT
        } else {
            TransactionKind.TRANSFER
        }

    /** True when this draft is a one-sided move to/from another person rather than a transfer. */
    fun isPaymentToPerson(draft: Draft): Boolean =
        (draft.from == null || draft.to == null) && draft.outside == OutsideParty.SOMEONE_ELSE

    /** The kind of one leg: a payment to/from a person is plain Spent/Received, anything else follows [kindFor]. */
    fun legKind(draft: Draft, direction: TransactionDirection): TransactionKind =
        if (isPaymentToPerson(draft)) TransactionKind.defaultFor(direction) else kindFor(draft)

    /**
     * The (out, in) legs, unsaved and unlinked -- the caller inserts both and
     * links them in one transaction. A null endpoint yields a null leg.
     */
    fun build(draft: Draft, label: String): Pair<Transaction?, Transaction?> {
        require(validate(draft) == null) { validate(draft)!! }
        val toPerson = isPaymentToPerson(draft)
        val note = draft.note?.trim()?.takeIf { it.isNotEmpty() }
        fun leg(end: Endpoint, direction: TransactionDirection, amount: BigDecimal, other: Endpoint?): Transaction {
            val kind = legKind(draft, direction)
            val who = other?.name ?: if (toPerson) draft.person?.takeIf { it.isNotBlank() } ?: "someone else" else "outside"
            return Transaction(
            accountId = end.accountId,
            amount = amount,
            currencyCode = end.currencyCode,
            direction = direction,
            merchant = note ?: when (direction) {
                TransactionDirection.DEBIT -> (if (toPerson) "Sent" else kind.label) + " to $who"
                TransactionDirection.CREDIT -> (if (toPerson) "Received" else kind.label) + " from $who"
            },
            source = TransactionSource.MANUAL,
            matchedRuleName = label,
            timestampMillis = draft.timestampMillis,
            person = draft.person,
            spendingCategory = SpendingCategory.TRANSFERS,
            kind = kind,
            kindUserSet = true,
            )
        }
        val out = draft.from?.let { leg(it, TransactionDirection.DEBIT, draft.amount, draft.to) }
        val inn = draft.to?.let { leg(it, TransactionDirection.CREDIT, draft.toAmount ?: draft.amount, draft.from) }
        return out to inn
    }
}
