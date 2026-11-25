package com.xemmy.mmbank.domain.export

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal

/**
 * One transaction as the export shows it: names instead of ids, a signed amount, tags
 * spelled out. Both formatters read this, so CSV and JSON can never disagree.
 */
data class ExportRow(
    val id: Long,
    val timestampMillis: Long,
    val kind: TransactionKind,
    val direction: TransactionDirection,
    /** Negative = money out, positive = money in. */
    val amount: BigDecimal,
    val currency: String,
    /** Signed amount in the base currency; null when no rate is known. */
    val amountInBase: BigDecimal?,
    val merchant: String?,
    val category: String?,
    val parentCategory: String?,
    val account: String?,
    val person: String?,
    val tags: List<String>,
    val note: String?,
    val source: String,
    val sender: String?,
    val billRef: String?,
    val excludedFromStats: Boolean,
    val historyOnly: Boolean,
    val linkedTransactionId: Long?,
    val refundOfId: Long?,
    val reportedBalance: BigDecimal?,
)

object ExportRows {

    /**
     * Money out is negative. Spend and cash withdrawals are out, income, refunds and cashback
     * are in; transfers, card payments and adjustments follow their own direction (an
     * adjustment already stored negative stays negative).
     */
    fun signedAmount(tx: Transaction): BigDecimal {
        val magnitude = tx.amount.abs()
        val out = when (tx.kind) {
            TransactionKind.SPEND, TransactionKind.ATM -> true
            TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.CASHBACK -> false
            TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT, TransactionKind.ADJUSTMENT ->
                tx.amount.signum() < 0 || tx.direction == TransactionDirection.DEBIT
        }
        return if (out) magnitude.negate() else magnitude
    }

    /**
     * Rows in the order given. [accountNames] should hold every account, archived ones too,
     * or their rows export without a name. [toBase] converts a positive amount to the base
     * currency (already rounded), or null when it can't.
     */
    fun build(
        transactions: List<Transaction>,
        accountNames: Map<Long, String>,
        categories: List<Category>,
        tagNamesByTransaction: Map<Long, List<String>>,
        toBase: (amount: BigDecimal, currency: String) -> BigDecimal?,
    ): List<ExportRow> {
        val categoryById = categories.associateBy { it.id }
        return transactions.map { tx ->
            val signed = signedAmount(tx)
            val cat = tx.categoryId?.let(categoryById::get)
            val parent = cat?.parentCategoryId?.let(categoryById::get)
            val base = toBase(tx.amount.abs(), tx.currencyCode)?.let { if (signed.signum() < 0) it.negate() else it }
            ExportRow(
                id = tx.id,
                timestampMillis = tx.timestampMillis,
                kind = tx.kind,
                direction = tx.direction,
                amount = signed,
                currency = tx.currencyCode,
                amountInBase = base,
                merchant = tx.merchant?.takeIf { it.isNotBlank() },
                category = cat?.name,
                parentCategory = parent?.name,
                account = tx.accountId?.let(accountNames::get),
                person = tx.person?.takeIf { it.isNotBlank() },
                tags = tagNamesByTransaction[tx.id].orEmpty(),
                note = tx.note?.takeIf { it.isNotBlank() },
                source = tx.source.name,
                sender = tx.sender?.takeIf { it.isNotBlank() },
                billRef = tx.billRef?.takeIf { it.isNotBlank() },
                excludedFromStats = tx.excludeFromStats,
                historyOnly = tx.historyOnly,
                linkedTransactionId = tx.linkedTransactionId,
                refundOfId = tx.refundOfId,
                reportedBalance = tx.reportedBalance,
            )
        }
    }
}
