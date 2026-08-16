package com.xemmy.mmbank.domain.model

/**
 * Bridges the legacy [SpendingCategory] enum onto the dynamic [Category]
 * table by name -- the single source of truth both the v21->v22 migration's
 * `categoryId` backfill and [com.xemmy.mmbank.domain.parser.CaptureCategoryResolver]'s
 * caller (at capture time) use to resolve an enum guess onto a real category.
 *
 * [SpendingCategory.OTHER] folds into "Uncategorized" rather than getting its
 * own dynamic category: [com.xemmy.mmbank.domain.parser.MerchantCategorizer]
 * never produces it (its fallback is UNCATEGORIZED), and its only real
 * writers are `excludeFromStats` balance-adjustment rows and demo data --
 * a dedicated bucket would be permanently near-empty.
 */
object CategoryMigrationMapping {

    val ENUM_TO_CATEGORY_NAME: Map<SpendingCategory, String> = mapOf(
        SpendingCategory.UNCATEGORIZED to "Uncategorized",
        SpendingCategory.GROCERIES to "Groceries",
        SpendingCategory.DINING to "Dining",
        SpendingCategory.TRANSPORT to "Transport",
        SpendingCategory.FUEL to "Fuel",
        SpendingCategory.SHOPPING to "Shopping",
        SpendingCategory.UTILITIES to "Utilities",
        SpendingCategory.ENTERTAINMENT to "Entertainment",
        SpendingCategory.HEALTH to "Health",
        SpendingCategory.TRAVEL to "Travel",
        SpendingCategory.TRANSFERS to "Transfers",
        SpendingCategory.INCOME to "Income",
        SpendingCategory.FEES to "Fees",
        SpendingCategory.OTHER to "Uncategorized",
    )
}
