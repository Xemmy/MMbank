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
    const val PLAN = "plan"
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
    INSIGHTS(Routes.INSIGHTS, "Insights"),
    PLAN(Routes.PLAN, "Plan"),
    ACCOUNTS(Routes.ACCOUNTS, "Accounts"),
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
        Routes.CASHBACK to AppTab.PLAN,
        Routes.ANALYTICS to AppTab.INSIGHTS,
        Routes.SUBSCRIPTIONS to AppTab.PLAN,
        Routes.GOALS to AppTab.PLAN,
        Routes.BILLS to AppTab.PLAN,
        Routes.SPLITS to AppTab.PLAN,
        "splits_group" to AppTab.PLAN,
        Routes.CATEGORIES to AppTab.PLAN,
        Routes.MONTHLY_REPORT to AppTab.INSIGHTS,
        Routes.ACCOUNTS to AppTab.ACCOUNTS,
        Routes.DEBT_PLANNER to AppTab.PLAN,
        Routes.INTEGRITY_CHECK to AppTab.ACCOUNTS,
        Routes.PLAN to AppTab.PLAN,
    )

    /**
     * Which tab owns [route], or null when none should be highlighted
     * (merchant detail, Copilot and everything under Settings, which is
     * reached from the gear on each tab root rather than from the bar).
     */
    fun tabFor(route: String?): AppTab? {
        val base = baseRoute(route) ?: return null
        return ownerByBaseRoute[base]
    }

    /**
     * Crash-loop breaker: after a crash, skip Home (the prime suspect) and
     * open Settings, where Diagnostics and Backup are still reachable.
     */
    fun startRoute(recoveredFromCrash: Boolean): String =
        if (recoveredFromCrash) Routes.SETTINGS else Routes.HOME
}

/** How a navigation step should animate. */
enum class NavTransitionKind {
    /** Between two tab roots: a short fade-through, no spatial movement. */
    TAB_SWITCH,

    /** Into a child screen: slides in from the end edge. */
    FORWARD,

    /** Back out of a child screen: slides toward the end edge. */
    BACK,

    /** Reduce-motion, or the same destination: no animation. */
    NONE,
}

object NavTransitions {
    const val FADE_MILLIS = 150
    const val SLIDE_MILLIS = 280

    private val tabRoots: Set<String> = AppTab.values().map { it.route }.toSet()

    fun isTabRoot(route: String?): Boolean = TabRouting.baseRoute(route) in tabRoots

    /**
     * Animation for moving from [from] to [to]. [isPop] is true when the
     * step is a back navigation. Tab root to tab root is a fade-through; any
     * step involving a child screen slides; reduce-motion disables it all.
     */
    fun kind(from: String?, to: String?, isPop: Boolean, reduceMotion: Boolean): NavTransitionKind {
        if (reduceMotion) return NavTransitionKind.NONE
        val a = TabRouting.baseRoute(from)
        val b = TabRouting.baseRoute(to)
        if (a == null || b == null || a == b) return NavTransitionKind.NONE
        if (isTabRoot(a) && isTabRoot(b)) return NavTransitionKind.TAB_SWITCH
        return if (isPop) NavTransitionKind.BACK else NavTransitionKind.FORWARD
    }
}
