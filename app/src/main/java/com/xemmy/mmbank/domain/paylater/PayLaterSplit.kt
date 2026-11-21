package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * Builds a Pay Later plan from a captured charge in one step ("Split with Tabby ×X"). Pure.
 *
 * - **Installment mode** ([chargeIsInstallment]): the SMS was one payment of X (e.g. AED 100 of a
 *   AED 400 ×4). The total is amount × X; installment #1 is that very charge (paid, linked to it)
 *   and the rest follow by cadence from the charge date. Later payments count as spending
 *   ([PayLaterPayment.shapeOf]) because only 1/X was counted at capture.
 * - **Full-price mode**: the charge is the whole price; today's behaviour, with the first
 *   installment already paid at the charge date.
 */
object PayLaterSplit {

    data class Split(val plan: PayLaterPlan, val installments: List<PayLaterInstallment>)

    fun total(charge: Transaction, count: Int, chargeIsInstallment: Boolean): BigDecimal =
        if (chargeIsInstallment) charge.amount.multiply(BigDecimal(count)) else charge.amount

    fun fromCharge(
        charge: Transaction,
        count: Int,
        chargeIsInstallment: Boolean,
        cadence: PayLaterCadence,
        zone: ZoneId = ZoneId.systemDefault(),
        provider: String = "Tabby",
        nowMillis: Long = System.currentTimeMillis(),
    ): Split {
        require(count in PayLaterSchedule.MIN_COUNT..PayLaterSchedule.MAX_COUNT) { "count out of range" }
        val total = total(charge, count, chargeIsInstallment)
        val chargeDate = Instant.ofEpochMilli(charge.timestampMillis).atZone(zone).toLocalDate()
        val generated = PayLaterSchedule.generate(total, count, chargeDate, cadence, zone, firstPaid = true)
        val installments = generated.mapIndexed { i, inst ->
            if (i == 0) {
                inst.copy(
                    paidAtMillis = charge.timestampMillis,
                    paymentTransactionId = if (chargeIsInstallment) charge.id else null,
                )
            } else {
                inst
            }
        }
        val plan = PayLaterPlan(
            merchant = charge.merchant?.takeIf { it.isNotBlank() } ?: provider,
            provider = provider.trim().ifEmpty { "Tabby" },
            totalAmount = total,
            currencyCode = charge.currencyCode,
            installmentCount = count,
            cadence = cadence,
            payFromAccountId = charge.accountId,
            sourceTransactionId = charge.id,
            categoryId = charge.categoryId,
            createdAt = nowMillis,
            sourceIsInstallment = chargeIsInstallment,
        )
        return Split(plan, installments)
    }

    /** Whether later payments of a split count as spending, for the sheet's one-line explanation. */
    fun laterPaymentsCountAsSpending(chargeIsInstallment: Boolean): Boolean = chargeIsInstallment
}
