package com.xemmy.mmbank.ui.navigation

/**
 * Pure route model for the app's single NavHost (Improvement Plan Phase 3 /
 * delivery step 5). No Android or Compose types here, so route strings, tab
 * ownership and the crash-recovery start rule are unit-testable on the JVM.
 *
 * Builders take already URI-encoded arguments: the caller applies
 * `Uri.encode` (android.net) so this file stays framework-free.
 */
object Routes {
    // Tab roots
    const val HOME = "home"
    const val ACTIVITY = "transactions"
    const val INSIGHTS = "insights"
    const val ACCOUNTS = "accounts"
    const val SETTINGS = "settings"

    // Patterns registered with composable(route = ...)
    const val ACTIVITY_PATTERN = "transactions?accountFilter={accountFilter}&drill={drill}"
    const val MERCHANT_DETAIL_PATTERN = "merchant_detail/{merchant}"
    const val SPLITS_GROUP_PATTERN = "splits_group/{groupId}"
    const val RULE_EDITOR_PATTERN = "rule_editor?ruleId={ruleId}"

    // Plain destinations
    const val UNCATEGORIZED_QUEUE = "uncategorized_queue"
    const val REVIEW_TRANSACTIONS = "review_transactions"
    const val INTEGRITY_CHECK = "integrity_check"
    const val ANALYTICS = "analytics"
    const val DEBT_PLANNER = "debt_planner"
    const val CASHBACK = "cashback"
    const val SUBSCRIPTIONS = "subscriptions"
    const val GOALS = "goals"
    const val BILLS = "bills"
    const val SPLITS = "splits"
    const val CATEGORIES = "categories"
    const val MONTHLY_REPORT = "monthly_report"
    const val COPILOT = "copilot"
    const val RULES = "rules"
    const val SETTINGS_CAPTURE = "settings_capture"

    /** Activity, pre-filtered to transactions that belong to no account. */
    const val ACTIVITY_UNASSIGNED = "transactions?accountFilter=unassigned"

    const val NEW_RULE = "rule_editor?ruleId=-1"

    /** [encodedMerchant] must already be URI-encoded. */
    fun merchantDetail(encodedMerchant: String) = "merchant_detail/$encodedMerchant"

    fun splitsGroup(groupId: Long) = "splits_group/$groupId"

    /** [encodedDrill] must already be URI-encoded. */
    fun activityDrill(encodedDrill: String) = "transactions?drill=$encodedDrill"

    fun ruleEditor(ruleId: Long) = "rule_editor?ruleId=$ruleId"
}

/** The bottom-bar destinations, in display order. */
enum class AppTab(val route: String, val label: String) {
    HOME(Routes.HOME, "Home"),
    ACTIVITY(Routes.ACTIVITY, "Activity"),
    INSIGHTS(Routes.INSIGHTS, "Dashboard"),
    ACCOUNTS(Routes.ACCOUNTS, "Accounts"),
    SETTINGS(Routes.SETTINGS, "Settings"),
}

object TabRouting {

    /**
     * Route with its query string and path arguments removed, so
     * "transactions?accountFilter={accountFilter}&drill={drill}" and
     * "merchant_detail/Uber" reduce to "transactions" and "merchant_detail".
     */
    fun baseRoute(route: String?): String? =
        route?.substringBefore('?')?.substringBefore('/')?.takeIf { it.isNotEmpty() }

    /** Destinations and the tab that should stay highlighted while they are open. */
    private val ownerByBaseRoute: Map<String, AppTab> = mapOf(
        Routes.HOME to AppTab.HOME,
        Routes.REVIEW_TRANSACTIONS to AppTab.HOME,
        Routes.ACTIVITY to AppTab.ACTIVITY,
        Routes.UNCATEGORIZED_QUEUE to AppTab.ACTIVITY,
        Routes.INSIGHTS to AppTab.INSIGHTS,
        Routes.CASHBACK to AppTab.INSIGHTS,
        Routes.ANALYTICS to AppTab.INSIGHTS,
        Routes.SUBSCRIPTIONS to AppTab.INSIGHTS,
        Routes.GOALS to AppTab.INSIGHTS,
        Routes.BILLS to AppTab.INSIGHTS,
        Routes.SPLITS to AppTab.INSIGHTS,
        "splits_group" to AppTab.INSIGHTS,
        Routes.CATEGORIES to AppTab.INSIGHTS,
        Routes.MONTHLY_REPORT to AppTab.INSIGHTS,
        Routes.ACCOUNTS to AppTab.ACCOUNTS,
        Routes.DEBT_PLANNER to AppTab.ACCOUNTS,
        Routes.INTEGRITY_CHECK to AppTab.ACCOUNTS,
        Routes.SETTINGS to AppTab.SETTINGS,
        Routes.RULES to AppTab.SETTINGS,
        "rule_editor" to AppTab.SETTINGS,
    )

    /**
     * Which tab owns [route], or null when none should be highlighted
     * (merchant detail and Copilot are reached from several tabs). Every
     * "settings_*" subpage belongs to Settings.
     */
    fun tabFor(route: String?): AppTab? {
        val base = baseRoute(route) ?: return null
        if (base.startsWith("settings_")) return AppTab.SETTINGS
        return ownerByBaseRoute[base]
    }

    /**
     * Crash-loop breaker: after a crash, skip Home (the prime suspect) and
     * open Settings, where Diagnostics and Backup are still reachable.
     */
    fun startRoute(recoveredFromCrash: Boolean): String =
        if (recoveredFromCrash) Routes.SETTINGS else Routes.HOME
}
