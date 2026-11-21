package com.xemmy.mmbank.domain.parser

/**
 * Decides a freshly captured transaction's `categoryId`
 * (see [com.xemmy.mmbank.data.repository.TransactionRepositoryImpl.recordParsed]).
 * A confirmed [com.xemmy.mmbank.domain.model.MerchantCategoryMapping] hit
 * always wins outright. Failing that, a pending AI suggestion blocks any
 * auto-assignment here -- it must still surface for human review in the
 * Uncategorized queue rather than being silently applied. Only when neither
 * applies does [bridgedCategoryId] -- [MerchantCategorizer]'s keyword guess
 * bridged onto a same-named dynamic category via
 * [com.xemmy.mmbank.domain.model.CategoryMigrationMapping] -- get used.
 *
 * Pure and top-level so it's JVM-testable.
 */
object CaptureCategoryResolver {

    fun resolve(
        confirmedCategoryId: Long?,
        hasPendingSuggestion: Boolean,
        bridgedCategoryId: Long?,
    ): Long? = confirmedCategoryId ?: bridgedCategoryId.takeUnless { hasPendingSuggestion }
}
