package com.xemmy.mmbank.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {

    @Test fun baseRoute_stripsQueryAndPathArgs() {
        assertEquals("transactions", TabRouting.baseRoute(Routes.ACTIVITY_PATTERN))
        assertEquals("transactions", TabRouting.baseRoute(Routes.ACTIVITY_UNASSIGNED))
        assertEquals("merchant_detail", TabRouting.baseRoute(Routes.MERCHANT_DETAIL_PATTERN))
        assertEquals("rule_editor", TabRouting.baseRoute(Routes.NEW_RULE))
        assertEquals("home", TabRouting.baseRoute("home"))
        assertNull(TabRouting.baseRoute(null))
        assertNull(TabRouting.baseRoute(""))
    }

    @Test fun everyTabRootOwnsItself() {
        AppTab.values().forEach { assertEquals(it, TabRouting.tabFor(it.route)) }
    }

    @Test fun tabOrderAndLabelsAreStable() {
        assertEquals(
            listOf("Home", "Activity", "Insights", "Plan", "Accounts"),
            AppTab.values().map { it.label },
        )
        assertEquals(5, AppTab.values().map { it.route }.toSet().size)
    }

    @Test fun detailPagesKeepInsightsHighlighted() {
        assertEquals(AppTab.INSIGHTS, TabRouting.tabFor(Routes.SPENDING_DETAIL))
        assertEquals(AppTab.INSIGHTS, TabRouting.tabFor(Routes.CASH_FLOW_DETAIL))
    }

    @Test fun transactionEditorRouteBuildsAndKeepsActivityHighlighted() {
        assertEquals("transaction_editor?id=-1", Routes.transactionEditor())
        assertEquals("transaction_editor?id=42", Routes.transactionEditor(42))
        assertEquals("transaction_editor?id=-1&bill=7", Routes.transactionEditorForBill(7))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.transactionEditorForBill(7)))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.TRANSACTION_EDITOR_PATTERN))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.transactionEditor(7)))
    }

    @Test fun activityVariantsKeepActivityHighlighted() {
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.ACTIVITY_PATTERN))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.ACTIVITY_UNASSIGNED))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.activityDrill("a%20b")))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabFor(Routes.UNCATEGORIZED_QUEUE))
    }

    @Test fun settingsAndItsSubpagesHighlightNoTab() {
        listOf(
            Routes.SETTINGS, Routes.SETTINGS_CAPTURE, "settings_backup", "settings_ai", Routes.RULES,
            Routes.RULE_EDITOR_PATTERN, Routes.ruleEditor(7),
        ).forEach { assertNull(it, TabRouting.tabFor(it)) }
    }

    @Test fun yearInReviewLivesUnderInsights() {
        assertEquals(AppTab.INSIGHTS, TabRouting.tabFor(Routes.YEAR_IN_REVIEW))
    }

    @Test fun accountHistoryLivesUnderAccounts() {
        assertEquals("account_history", TabRouting.baseRoute(Routes.ACCOUNT_HISTORY_PATTERN))
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabFor(Routes.ACCOUNT_HISTORY_PATTERN))
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabFor(Routes.accountHistory(12)))
        assertEquals("account_history/12", Routes.accountHistory(12))
        assertEquals("account_history/12?drift=true", Routes.accountHistory(12, drift = true))
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabFor(Routes.accountHistory(12, drift = true)))
        assertTrue(Routes.ACCOUNT_HISTORY_PATTERN.startsWith("account_history/"))
        assertTrue(Routes.ACCOUNT_HISTORY_PATTERN.contains("{accountId}"))
    }

    @Test fun statementReconcileLivesUnderAccounts() {
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabFor(Routes.STATEMENT_RECONCILE))
    }

    @Test fun searchHighlightsNoTabAndIsNotATabRoot() {
        assertNull(TabRouting.tabFor(Routes.SEARCH))
        assertTrue(!NavTransitions.isTabRoot(Routes.SEARCH))
        assertEquals(NavTransitionKind.FORWARD, NavTransitions.kind(Routes.HOME, Routes.SEARCH, false, false))
    }

    @Test fun planToolsHighlightPlan() {
        listOf(
            Routes.CASHBACK, Routes.SUBSCRIPTIONS, Routes.GOALS, Routes.BILLS, Routes.SPLITS,
            Routes.SPLITS_GROUP_PATTERN, Routes.splitsGroup(3), Routes.CATEGORIES, Routes.BUDGETS, Routes.DEBT_PLANNER,
        ).forEach { assertEquals(it, AppTab.PLAN, TabRouting.tabFor(it)) }
    }

    @Test fun insightsAccountsAndHomeChildren() {
        listOf(
            Routes.ANALYTICS, Routes.MONTHLY_REPORT,
        ).forEach { assertEquals(it, AppTab.INSIGHTS, TabRouting.tabFor(it)) }
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabFor(Routes.INTEGRITY_CHECK))
        assertEquals(AppTab.HOME, TabRouting.tabFor(Routes.REVIEW_TRANSACTIONS))
    }

    @Test fun sharedScreensHighlightNoTab() {
        assertNull(TabRouting.tabFor(Routes.MERCHANT_DETAIL_PATTERN))
        assertNull(TabRouting.tabFor(Routes.merchantDetail("Uber")))
        assertNull(TabRouting.tabFor(Routes.COPILOT))
        assertNull(TabRouting.tabFor("unknown_route"))
        assertNull(TabRouting.tabFor(null))
    }

    @Test fun builders() {
        assertEquals("merchant_detail/Uber%20Eats", Routes.merchantDetail("Uber%20Eats"))
        assertEquals("splits_group/42", Routes.splitsGroup(42))
        assertEquals("transactions?drill=x", Routes.activityDrill("x"))
        assertEquals("rule_editor?ruleId=9", Routes.ruleEditor(9))
        assertEquals("rule_editor?ruleId=-1", Routes.NEW_RULE)
    }

    @Test fun patternsAreConsistentWithBuilders() {
        assertTrue(Routes.ACTIVITY_PATTERN.startsWith(Routes.ACTIVITY + "?"))
        assertTrue(Routes.ACTIVITY_PATTERN.contains("{accountFilter}"))
        assertTrue(Routes.ACTIVITY_PATTERN.contains("{drill}"))
        assertTrue(Routes.ACTIVITY_UNASSIGNED.startsWith(Routes.ACTIVITY + "?accountFilter="))
        assertTrue(Routes.RULE_EDITOR_PATTERN.startsWith("rule_editor?ruleId="))
        assertTrue(Routes.MERCHANT_DETAIL_PATTERN.startsWith("merchant_detail/"))
        assertTrue(Routes.SPLITS_GROUP_PATTERN.startsWith("splits_group/"))
    }

    @Test fun crashRecoveryStartRoute() {
        assertEquals("home", TabRouting.startRoute(recoveredFromCrash = false))
        assertEquals("settings", TabRouting.startRoute(recoveredFromCrash = true))
    }

    // Stacks as the NavController holds them: the graph entry (null route) at the
    // bottom, Home as the start destination, route patterns rather than built routes.
    private fun stack(vararg routes: String?): List<String?> = listOf(null, *routes)

    @Test fun planScreenOpenedFromInsightsKeepsInsightsHighlighted() {
        listOf(Routes.SUBSCRIPTIONS, Routes.BILLS, Routes.GOALS, Routes.SPLITS, Routes.BUDGETS, Routes.CASHBACK)
            .forEach { child ->
                assertEquals(child, AppTab.INSIGHTS, TabRouting.tabForStack(stack(Routes.HOME, Routes.INSIGHTS, child)))
            }
        assertEquals(
            AppTab.INSIGHTS,
            TabRouting.tabForStack(stack(Routes.HOME, Routes.INSIGHTS, Routes.SPLITS, Routes.SPLITS_GROUP_PATTERN)),
        )
    }

    @Test fun planScreenOpenedFromPlanKeepsPlanHighlighted() {
        assertEquals(AppTab.PLAN, TabRouting.tabForStack(stack(Routes.HOME, Routes.PLAN, Routes.SUBSCRIPTIONS)))
        assertEquals(AppTab.PLAN, TabRouting.tabForStack(stack(Routes.HOME, Routes.PLAN)))
    }

    @Test fun homeChildrenHighlightHome() {
        assertEquals(AppTab.HOME, TabRouting.tabForStack(stack(Routes.HOME)))
        assertEquals(AppTab.HOME, TabRouting.tabForStack(stack(Routes.HOME, Routes.CASH_FLOW_DETAIL)))
        assertEquals(AppTab.HOME, TabRouting.tabForStack(stack(Routes.HOME, Routes.REVIEW_TRANSACTIONS)))
    }

    @Test fun tabRootOpenedFromHomeHighlightsThatTab() {
        assertEquals(AppTab.ACCOUNTS, TabRouting.tabForStack(stack(Routes.HOME, Routes.ACCOUNTS)))
        assertEquals(AppTab.ACTIVITY, TabRouting.tabForStack(stack(Routes.HOME, Routes.ACTIVITY_PATTERN)))
        assertEquals(
            AppTab.ACCOUNTS,
            TabRouting.tabForStack(stack(Routes.HOME, Routes.ACCOUNTS, Routes.ACCOUNT_HISTORY_PATTERN)),
        )
    }

    @Test fun unownedScreensStillHighlightNoTabWhateverTheStack() {
        listOf(Routes.SETTINGS, Routes.SEARCH, Routes.COPILOT, Routes.MERCHANT_DETAIL_PATTERN).forEach { top ->
            assertNull(top, TabRouting.tabForStack(stack(Routes.HOME, Routes.INSIGHTS, top)))
        }
    }

    @Test fun stackWithoutATabRootFallsBackToTheOwner() {
        // Crash-recovery start: Settings is the start destination, no tab root below.
        assertNull(TabRouting.tabForStack(stack(Routes.SETTINGS)))
        assertEquals(AppTab.PLAN, TabRouting.tabForStack(stack(Routes.SETTINGS, Routes.CATEGORIES)))
        assertNull(TabRouting.tabForStack(emptyList()))
    }

    @Test fun reTappingTheTabYouAreInPopsToItsRoot() {
        val insightsSubs = stack(Routes.HOME, Routes.INSIGHTS, Routes.SUBSCRIPTIONS)
        assertEquals(2, TabRouting.reselectRootIndex(insightsSubs, AppTab.INSIGHTS))
        // Settings opened from the Insights gear: tapping Insights goes back to it too.
        assertEquals(2, TabRouting.reselectRootIndex(stack(Routes.HOME, Routes.INSIGHTS, Routes.SETTINGS), AppTab.INSIGHTS))
        assertEquals(1, TabRouting.reselectRootIndex(stack(Routes.HOME, Routes.REVIEW_TRANSACTIONS), AppTab.HOME))
        assertEquals(
            2,
            TabRouting.reselectRootIndex(
                stack(Routes.HOME, Routes.ACTIVITY_PATTERN, Routes.TRANSACTION_EDITOR_PATTERN),
                AppTab.ACTIVITY,
            ),
        )
    }

    @Test fun otherTabsAndTheRootItselfUseTheNormalSwitch() {
        val insightsSubs = stack(Routes.HOME, Routes.INSIGHTS, Routes.SUBSCRIPTIONS)
        assertNull(TabRouting.reselectRootIndex(insightsSubs, AppTab.PLAN))
        assertNull(TabRouting.reselectRootIndex(insightsSubs, AppTab.HOME))
        assertNull(TabRouting.reselectRootIndex(stack(Routes.HOME, Routes.INSIGHTS), AppTab.INSIGHTS))
        assertNull(TabRouting.reselectRootIndex(stack(Routes.HOME), AppTab.HOME))
        assertNull(TabRouting.reselectRootIndex(stack(Routes.SETTINGS), AppTab.HOME))
        assertNull(TabRouting.reselectRootIndex(emptyList(), AppTab.HOME))
    }

    @Test fun transitionKinds() {
        val k = { f: String?, t: String?, pop: Boolean, rm: Boolean -> NavTransitions.kind(f, t, pop, rm) }
        assertEquals(NavTransitionKind.TAB_SWITCH, k(Routes.HOME, Routes.ACTIVITY_PATTERN, false, false))
        assertEquals(NavTransitionKind.TAB_SWITCH, k(Routes.PLAN, Routes.HOME, true, false))
        assertEquals(NavTransitionKind.FORWARD, k(Routes.INSIGHTS, Routes.GOALS, false, false))
        assertEquals(NavTransitionKind.BACK, k(Routes.GOALS, Routes.INSIGHTS, true, false))
        assertEquals(NavTransitionKind.FORWARD, k(Routes.HOME, Routes.merchantDetail("Uber"), false, false))
        assertEquals(NavTransitionKind.NONE, k(Routes.HOME, Routes.ACTIVITY, false, true))
        assertEquals(NavTransitionKind.NONE, k(Routes.ACTIVITY_PATTERN, Routes.ACTIVITY_UNASSIGNED, false, false))
        assertEquals(NavTransitionKind.NONE, k(null, Routes.HOME, false, false))
        assertEquals(NavTransitionKind.FORWARD, k(Routes.HOME, Routes.SETTINGS, false, false))
        assertTrue(NavTransitions.isTabRoot(Routes.PLAN))
        assertTrue(!NavTransitions.isTabRoot(Routes.SETTINGS))
        assertTrue(NavTransitions.isTabRoot(Routes.ACTIVITY_UNASSIGNED))
        assertTrue(!NavTransitions.isTabRoot(Routes.GOALS))
    }
}

class AdaptiveLayoutTest {
    @Test fun compactWidthsUseBottomBar() {
        assertEquals(NavChrome.BOTTOM_BAR, AdaptiveLayout.chrome(360))
        assertEquals(NavChrome.BOTTOM_BAR, AdaptiveLayout.chrome(599))
    }

    @Test fun mediumAndWiderUseRail() {
        assertEquals(NavChrome.RAIL, AdaptiveLayout.chrome(600))
        assertEquals(NavChrome.RAIL, AdaptiveLayout.chrome(1280))
    }

    @Test fun contentWidthIsCapped() {
        assertEquals(360, AdaptiveLayout.contentWidthDp(360))
        assertEquals(640, AdaptiveLayout.contentWidthDp(640))
        assertEquals(640, AdaptiveLayout.contentWidthDp(1200))
        assertEquals(0, AdaptiveLayout.contentWidthDp(-5))
    }

    @Test fun payLaterRoutesKeepThePlanTabHighlighted() {
        assertEquals("pay_later_detail/9", Routes.payLaterDetail(9))
        assertEquals("pay_later_editor?planId=-1&txId=-1", Routes.payLaterEditor())
        assertEquals("pay_later_editor?planId=4&txId=-1", Routes.payLaterEditor(planId = 4))
        assertEquals("pay_later_editor?planId=-1&txId=12", Routes.payLaterEditor(fromTransactionId = 12))
        assertEquals(AppTab.PLAN, TabRouting.tabFor(Routes.PAY_LATER))
        assertEquals(AppTab.PLAN, TabRouting.tabFor(Routes.PAY_LATER_DETAIL_PATTERN))
        assertEquals(AppTab.PLAN, TabRouting.tabFor(Routes.payLaterDetail(9)))
        assertEquals(AppTab.PLAN, TabRouting.tabFor(Routes.PAY_LATER_EDITOR_PATTERN))
        assertEquals(AppTab.PLAN, TabRouting.tabFor(Routes.payLaterEditor(fromTransactionId = 12)))
    }

    @Test
    fun `every tab root maps to its own title`() {
        AppTab.values().forEach { assertEquals(it, TabRouting.tabFor(it.route)) }
        assertEquals("Insights", TabRouting.tabFor(Routes.INSIGHTS)?.label)
    }

    @Test
    fun `fab clearance clears the fab with its margin and a gap`() {
        assertTrue(com.xemmy.mmbank.ui.theme.MMSpacing.fabClearance.value >= 56f + 16f)
    }
}
