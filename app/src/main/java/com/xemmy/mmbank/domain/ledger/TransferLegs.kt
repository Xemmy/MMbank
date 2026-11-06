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
    )

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

    /**
     * The (out, in) legs, unsaved and unlinked -- the caller inserts both and
     * links them in one transaction. A null endpoint yields a null leg.
     */
    fun build(draft: Draft, label: String): Pair<Transaction?, Transaction?> {
        require(validate(draft) == null) { validate(draft)!! }
        val kind = kindFor(draft)
        val note = draft.note?.trim()?.takeIf { it.isNotEmpty() }
        fun leg(end: Endpoint, direction: TransactionDirection, amount: BigDecimal, other: Endpoint?) = Transaction(
            accountId = end.accountId,
            amount = amount,
            currencyCode = end.currencyCode,
            direction = direction,
            merchant = note ?: when (direction) {
                TransactionDirection.DEBIT -> "${kind.label} to ${other?.name ?: "outside"}"
                TransactionDirection.CREDIT -> "${kind.label} from ${other?.name ?: "outside"}"
            },
            source = TransactionSource.MANUAL,
            matchedRuleName = label,
            timestampMillis = draft.timestampMillis,
            person = draft.person,
            spendingCategory = SpendingCategory.TRANSFERS,
            kind = kind,
            kindUserSet = true,
        )
        val out = draft.from?.let { leg(it, TransactionDirection.DEBIT, draft.amount, draft.to) }
        val inn = draft.to?.let { leg(it, TransactionDirection.CREDIT, draft.toAmount ?: draft.amount, draft.from) }
        return out to inn
    }
}
