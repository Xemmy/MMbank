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

    @Test fun planToolsHighlightPlan() {
        listOf(
            Routes.CASHBACK, Routes.SUBSCRIPTIONS, Routes.GOALS, Routes.BILLS, Routes.SPLITS,
            Routes.SPLITS_GROUP_PATTERN, Routes.splitsGroup(3), Routes.CATEGORIES, Routes.DEBT_PLANNER,
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
