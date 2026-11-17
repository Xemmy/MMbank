package com.xemmy.mmbank.domain.layout

import com.xemmy.mmbank.domain.ReorderList

/** One movable box on a screen. Non-removable sections can be reordered but never hidden. */
data class SectionSpec(val id: String, val label: String, val removable: Boolean = true)

/** The user's chosen order of a screen's sections and which of them are hidden. */
data class ScreenLayout(val order: List<String>, val hidden: Set<String> = emptySet()) {
    val visibleOrder: List<String> get() = order.filter { it !in hidden }

    fun move(id: String, direction: Int): ScreenLayout = copy(order = ReorderList.move(order, id, direction))

    /** Flips visibility of [id]; a no-op for unknown or non-removable sections. */
    fun toggle(id: String, specs: List<SectionSpec>): ScreenLayout {
        val spec = specs.firstOrNull { it.id == id } ?: return this
        if (!spec.removable) return this
        return copy(hidden = if (id in hidden) hidden - id else hidden + id)
    }

    companion object {
        fun default(specs: List<SectionSpec>) = ScreenLayout(specs.map { it.id })
    }
}

/** Stores a layout as "a,b,c|x,y" (order | hidden). Decoding never throws. */
object ScreenLayoutCodec {
    fun encode(layout: ScreenLayout): String =
        layout.order.joinToString(",") + "|" + layout.hidden.joinToString(",")

    fun decode(raw: String?, defaults: List<SectionSpec>): ScreenLayout {
        val default = ScreenLayout.default(defaults)
        if (raw.isNullOrBlank()) return default
        val parts = raw.split("|")
        val known = defaults.map { it.id }.toSet()
        fun ids(s: String?) = s.orEmpty().split(",").map { it.trim() }.filter { it in known }
        val stored = ids(parts.getOrNull(0)).distinct().toMutableList()
        // Sections added in a later app version go after their default-order predecessor.
        val defaultIds = default.order
        defaultIds.forEachIndexed { index, id ->
            if (id in stored) return@forEachIndexed
            val predecessor = defaultIds.take(index).lastOrNull { it in stored }
            val at = if (predecessor == null) 0 else stored.indexOf(predecessor) + 1
            stored.add(at, id)
        }
        val nonRemovable = defaults.filter { !it.removable }.map { it.id }.toSet()
        val hidden = ids(parts.getOrNull(1)).filter { it !in nonRemovable }.toSet()
        return ScreenLayout(stored, hidden)
    }
}

object HomeSections {
    const val WEEKLY_DIGEST = "weekly_digest"
    const val NET_WORTH = "net_worth"
    const val FINANCIAL_HEALTH = "financial_health"
    const val MONTH_CARD = "month_card"
    const val ACCOUNTS = "accounts"
    const val CARDS_DUE = "cards_due"
    const val UNASSIGNED = "unassigned"
    const val CRYPTO = "crypto"
    const val RECENT = "recent"

    val specs = listOf(
        SectionSpec(WEEKLY_DIGEST, "Weekly digest"),
        SectionSpec(NET_WORTH, "Net worth"),
        SectionSpec(FINANCIAL_HEALTH, "Financial health"),
        SectionSpec(MONTH_CARD, "This month"),
        SectionSpec(ACCOUNTS, "Accounts"),
        SectionSpec(CARDS_DUE, "Cards due"),
        SectionSpec(UNASSIGNED, "Unassigned money", removable = false),
        SectionSpec(CRYPTO, "Crypto"),
        SectionSpec(RECENT, "Recent activity"),
    )
}

object InsightsSections {
    const val PERIOD_TILES = "period_tiles"
    const val INSIGHTS_CARD = "insights_card"
    const val NET_WORTH_TREND = "net_worth_trend"
    const val GOAL_CARD = "goal_card"
    const val CATEGORY_BUDGETS = "category_budgets_card"
    const val CASHFLOW = "cashflow_card"
    const val CATEGORY = "category_card"
    const val PERSON = "person_card"
    const val ANALYTICS = "analytics_row"
    const val SUBSCRIPTIONS = "subscriptions_row"
    const val BILLS = "bills_row"
    const val GOALS = "goals_row"
    const val SPLITS = "splits_row"
    const val BUDGETS = "budgets_row"
    const val REPORT = "report_row"
    const val COPILOT = "copilot_row"
    const val REWARDS = "rewards_row"

    val specs = listOf(
        SectionSpec(PERIOD_TILES, "Period totals"),
        SectionSpec(INSIGHTS_CARD, "Smart insights"),
        SectionSpec(NET_WORTH_TREND, "Net worth trend"),
        SectionSpec(GOAL_CARD, "Savings goal"),
        SectionSpec(CATEGORY_BUDGETS, "Category budgets"),
        SectionSpec(CASHFLOW, "Cash flow chart"),
        SectionSpec(CATEGORY, "Spending by category"),
        SectionSpec(PERSON, "Spending by person"),
        SectionSpec(ANALYTICS, "Spending analytics"),
        SectionSpec(SUBSCRIPTIONS, "Subscriptions radar"),
        SectionSpec(BILLS, "Upcoming bills"),
        SectionSpec(GOALS, "Savings goals"),
        SectionSpec(SPLITS, "Splits & groups"),
        SectionSpec(BUDGETS, "Budgets"),
        SectionSpec(REPORT, "Monthly report"),
        SectionSpec(COPILOT, "Financial Copilot"),
        SectionSpec(REWARDS, "Cashback rewards"),
    )
}
