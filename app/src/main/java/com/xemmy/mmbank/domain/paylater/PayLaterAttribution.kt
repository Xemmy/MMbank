package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.AssignmentSource
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction

/**
 * A split is one purchase: every row of a Pay Later plan -- the purchase it splits and each
 * installment payment -- is filed under the purchase's category and person. Pure.
 *
 * Capture decides category and person from the merchant name, and every installment payment is
 * named "Tabby" (Tabby's receipt, the bank's "at Tabby" charge), so on its own it filed a laptop's
 * payments and a pair of shoes' payments under the same "Tabby" guess. With "each payment when
 * paid" those payments are the rows that count as spending, so the guess is what budgets, the
 * category breakdown and per-person totals showed.
 *
 * The purchase (the plan's [PayLaterPlan.sourceTransactionId]) is the source of truth, with the
 * plan's own [PayLaterPlan.categoryId] as the fallback for a plan that splits no captured row.
 * Payments take its values with [AssignmentSource.PLAN], which merchant learning never overwrites
 * or learns from. A value the user set on a payment by hand is kept, except when the user just
 * re-filed the split itself ([refileFromEdit]), whose choice then applies to every row of it.
 */
object PayLaterAttribution {

    /**
     * What every row of a split is filed under. [person] can only come from the purchase row: a plan
     * that splits none ([hasPurchase] false) has no person to follow, so its payments keep theirs.
     */
    data class Attribution(val categoryId: Long?, val person: String?, val hasPurchase: Boolean = true)

    /** One field to set on the purchase row as the user's own choice ([value] null clears it). */
    data class Change<T>(val value: T?)

    /**
     * What a user's edit to one row of a split does to the rest of it ([refileFromEdit]): the
     * purchase row takes the new values as the user's own, the plan's category follows, and then
     * every payment is re-stamped from the purchase, overriding earlier per-row choices.
     */
    data class Refile(
        /** New category for the purchase row; null = leave it. */
        val sourceCategory: Change<Long>?,
        /** New person for the purchase row; null = leave it. */
        val sourcePerson: Change<String>?,
        /** The plan's own category afterwards. */
        val planCategoryId: Long?,
    )

    /** The values a plan's payments are filed under: the purchase's own, else the plan's category. */
    fun of(plan: PayLaterPlan, source: Transaction?): Attribution = Attribution(
        categoryId = source?.categoryId ?: plan.categoryId,
        person = source?.person?.trim()?.takeIf { it.isNotEmpty() },
        hasPurchase = source != null,
    )

    /** The plan [transactionId] belongs to, as the purchase it splits or one of its payments; null if none. */
    fun planOf(transactionId: Long, plans: List<PayLaterPlan>, installments: List<PayLaterInstallment>): PayLaterPlan? {
        if (transactionId == 0L) return null
        plans.firstOrNull { it.sourceTransactionId == transactionId }?.let { return it }
        val planId = installments.firstOrNull { it.paymentTransactionId == transactionId }?.planId ?: return null
        return plans.firstOrNull { it.id == planId }
    }

    /**
     * The payment rows of [plan]: the transactions its installments are linked to, minus the purchase
     * itself (a quick split's charge can be both its source and installment #1's payment).
     */
    fun paymentIds(plan: PayLaterPlan, installments: List<PayLaterInstallment>): List<Long> =
        installments
            .filter { it.planId == plan.id }
            .mapNotNull { it.paymentTransactionId }
            .filter { it != plan.sourceTransactionId }
            .distinct()

    /**
     * Payment rows whose category should be stamped with [attribution]'s. With a category to follow:
     * every row not already carrying it as [AssignmentSource.PLAN]. With none (the purchase is
     * uncategorized): only rows the plan stamped before, so they clear along with it. A row the user
     * filed by hand is left alone unless [overrideUser] -- the user just re-filed the whole split, so
     * every payment follows, and a cleared category clears on all of them.
     */
    fun idsToStampCategory(rows: List<Transaction>, attribution: Attribution, overrideUser: Boolean = false): List<Long> {
        val categoryId = attribution.categoryId
        return rows.filter { row ->
            if (!overrideUser && row.categorySource == AssignmentSource.USER) return@filter false
            if (categoryId == null) {
                row.categoryId != null && (overrideUser || row.categorySource == AssignmentSource.PLAN)
            } else {
                row.categoryId != categoryId || row.categorySource != AssignmentSource.PLAN
            }
        }.map { it.id }
    }

    /** As [idsToStampCategory], for the person; nothing when the plan splits no purchase row. */
    fun idsToStampPerson(rows: List<Transaction>, attribution: Attribution, overrideUser: Boolean = false): List<Long> {
        if (!attribution.hasPurchase) return emptyList()
        val person = attribution.person
        return rows.filter { row ->
            if (!overrideUser && row.personSource == AssignmentSource.USER) return@filter false
            val current = row.person?.trim()?.takeIf { it.isNotEmpty() }
            if (person == null) {
                current != null && (overrideUser || row.personSource == AssignmentSource.PLAN)
            } else {
                current != person || row.personSource != AssignmentSource.PLAN
            }
        }.map { it.id }
    }

    /**
     * The user changed the category ([categoryChanged]) and/or person ([personChanged]) of [edited],
     * a row of [plan]'s split. Edited on a payment, the new values become the purchase's, so the whole
     * split follows. Edited on the purchase, it already holds them. The plan's own category keeps step
     * either way: it is what a plan with no purchase row ([sourceId] null) is filed under.
     */
    fun refileFromEdit(
        edited: Transaction,
        plan: PayLaterPlan,
        sourceId: Long?,
        categoryChanged: Boolean,
        personChanged: Boolean,
    ): Refile {
        val onPayment = sourceId != null && edited.id != sourceId
        return Refile(
            sourceCategory = if (onPayment && categoryChanged) Change(edited.categoryId) else null,
            sourcePerson = if (onPayment && personChanged) Change(edited.person?.trim()?.takeIf { it.isNotEmpty() }) else null,
            planCategoryId = if (categoryChanged) edited.categoryId else plan.categoryId,
        )
    }

    /**
     * Whether a category picked on [transaction] may become a merchant rule. Never for a row
     * of a split ([inSplit]: the split is re-filed instead), nor for any row that paid a Pay Later
     * provider: its merchant ("Tabby") names who collected the money, not what was bought, so a rule
     * would file every other purchase's payments the same way. "Tabby Card" / "Tabby+" purchases
     * are ordinary merchants ([PayLaterProviders.providerOf]). Accounts still learn: the card that
     * pays Tabby is the same whatever was bought.
     */
    fun learnsMerchantRule(transaction: Transaction, inSplit: Boolean): Boolean =
        !inSplit && PayLaterProviders.providerOf(transaction.merchant, transaction.rawMessage, transaction.sender) == null
}
