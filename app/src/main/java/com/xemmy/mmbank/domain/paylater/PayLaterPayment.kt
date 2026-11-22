package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource

/**
 * Turns "I paid installment k" into the transaction(s) to record. Pure and JVM-testable; the
 * repository inserts the result and stamps the installment.
 *
 * Three shapes, so spending is never counted twice:
 *  - the purchase was already captured **and** a provider account is tracked: a linked transfer
 *    pair (the paying account goes down, the provider's liability goes down);
 *  - the purchase was already captured but no provider account: a debit hidden from analytics;
 *  - the purchase was never captured, or the captured SMS was itself one installment: an ordinary spend.
 */
object PayLaterPayment {

    enum class Shape { TRANSFER_PAIR, EXCLUDED_DEBIT, SPEND }

    /** [paying] is the leg that leaves the paying account; [counterpart] (a transfer's receiving leg) is null otherwise. */
    data class Legs(val shape: Shape, val paying: Transaction, val counterpart: Transaction?)

    fun shapeOf(plan: PayLaterPlan, hasProviderAccount: Boolean): Shape = when {
        // Only one installment was counted when the purchase was captured, so the rest are real spending.
        plan.sourceIsInstallment -> Shape.SPEND
        plan.sourceTransactionId != null && hasProviderAccount -> Shape.TRANSFER_PAIR
        plan.sourceTransactionId != null -> Shape.EXCLUDED_DEBIT
        else -> Shape.SPEND
    }

    /**
     * The shape an auto-matched captured debit (an installment SMS, see [PayLaterAutoMatch]) takes on,
     * by the same rule as [build]: SPEND leaves it a counted spend; EXCLUDED_DEBIT hides it from
     * spending, because the purchase was already counted in full when it was captured; TRANSFER_PAIR
     * turns it into a transfer to the provider's account. A transfer needs the paying account, so an
     * SMS filed to no account (or to the provider's own account) is hidden from spending instead.
     */
    fun matchedShape(plan: PayLaterPlan, matchedAccountId: Long?): Shape {
        val providerAccount = plan.payToAccountId?.takeIf { it != matchedAccountId }
        val shape = shapeOf(plan, hasProviderAccount = providerAccount != null)
        return if (shape == Shape.TRANSFER_PAIR && matchedAccountId == null) Shape.EXCLUDED_DEBIT else shape
    }

    fun describe(plan: PayLaterPlan, installment: PayLaterInstallment): String =
        "${plan.merchant} · Payment ${installment.number} of ${plan.installmentCount}"

    /**
     * The transactions for paying [installment] of [plan] from [from] on [paidAtMillis]. [to] is the provider's
     * account (used only for the transfer shape, and only when the plan names one).
     */
    fun build(
        plan: PayLaterPlan,
        installment: PayLaterInstallment,
        from: TransferLegs.Endpoint,
        to: TransferLegs.Endpoint?,
        paidAtMillis: Long,
    ): Legs {
        val shape = shapeOf(plan, hasProviderAccount = to != null)
        val note = describe(plan, installment)
        return when (shape) {
            Shape.TRANSFER_PAIR -> {
                val (out, inn) = TransferLegs.build(
                    TransferLegs.Draft(from = from, to = to, amount = installment.amount, timestampMillis = paidAtMillis, note = note),
                    label = LABEL,
                )
                Legs(shape, out!!, inn)
            }
            Shape.EXCLUDED_DEBIT, Shape.SPEND -> Legs(
                shape = shape,
                paying = Transaction(
                    accountId = from.accountId,
                    amount = installment.amount,
                    currencyCode = plan.currencyCode,
                    direction = TransactionDirection.DEBIT,
                    merchant = plan.merchant,
                    source = TransactionSource.MANUAL,
                    matchedRuleName = LABEL,
                    timestampMillis = paidAtMillis,
                    excludeFromStats = shape == Shape.EXCLUDED_DEBIT,
                    spendingCategory = if (shape == Shape.EXCLUDED_DEBIT) SpendingCategory.TRANSFERS else SpendingCategory.UNCATEGORIZED,
                    categoryId = plan.categoryId,
                    kind = TransactionKind.SPEND,
                    kindUserSet = true,
                    note = note,
                ),
                counterpart = null,
            )
        }
    }

    /** [installment] stamped as paid by transaction [paymentTransactionId]. */
    fun markPaid(installment: PayLaterInstallment, paymentTransactionId: Long?, paidAtMillis: Long): PayLaterInstallment =
        installment.copy(paidAtMillis = paidAtMillis, paymentTransactionId = paymentTransactionId)

    /** [installment] back to unpaid (undo). */
    fun markUnpaid(installment: PayLaterInstallment): PayLaterInstallment =
        installment.copy(paidAtMillis = null, paymentTransactionId = null)

    /** Rule label stamped on the transactions this feature records. */
    const val LABEL = "paylater.payment"
}
