package com.xemmy.mmbank.ui

import android.net.Uri
import android.view.WindowManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import com.xemmy.mmbank.ui.navigation.NavChrome
import com.xemmy.mmbank.ui.screens.search.SearchScreen
import com.xemmy.mmbank.ui.screens.statement.StatementReconcileScreen
import com.xemmy.mmbank.ui.screens.upcoming.UpcomingPaymentsScreen
import com.xemmy.mmbank.ui.screens.year.YearInReviewScreen
import com.xemmy.mmbank.ui.screens.search.SearchTarget
import com.xemmy.mmbank.ui.navigation.AdaptiveLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRail
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.xemmy.mmbank.ui.navigation.NavTransitionKind
import com.xemmy.mmbank.ui.navigation.NavTransitions
import com.xemmy.mmbank.ui.util.rememberReduceMotion
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.model.BackgroundStyle
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import com.xemmy.mmbank.ui.auth.LockScreen
import com.xemmy.mmbank.ui.navigation.AppTab
import com.xemmy.mmbank.ui.navigation.Routes
import com.xemmy.mmbank.ui.navigation.TabRouting
import com.xemmy.mmbank.ui.screens.integrity.IntegrityScreen
import com.xemmy.mmbank.ui.screens.accounts.AccountsScreen
import com.xemmy.mmbank.ui.screens.cashback.CashbackScreen
import com.xemmy.mmbank.ui.screens.accounts.DebtPlannerScreen
import com.xemmy.mmbank.ui.screens.copilot.CopilotChatScreen
import com.xemmy.mmbank.ui.screens.dashboard.DashboardScreen
import com.xemmy.mmbank.ui.screens.report.MonthlyReportScreen
import com.xemmy.mmbank.ui.screens.bills.BillsScreen
import com.xemmy.mmbank.ui.screens.goals.GoalsScreen
import com.xemmy.mmbank.ui.screens.insights.InsightsScreen
import com.xemmy.mmbank.ui.screens.accounts.AccountHistoryScreen
import com.xemmy.mmbank.ui.screens.merchant.MerchantDetailScreen
import com.xemmy.mmbank.ui.screens.analytics.AnalyticsScreen
import com.xemmy.mmbank.ui.screens.review.ReviewTransactionsScreen
import com.xemmy.mmbank.ui.screens.rules.RuleEditorScreen
import com.xemmy.mmbank.ui.screens.rules.RulesScreen
import com.xemmy.mmbank.ui.screens.subscriptions.SubscriptionsScreen
import com.xemmy.mmbank.ui.screens.plan.PlanScreen
import com.xemmy.mmbank.ui.screens.settings.AiSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.AppearanceSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.BackupSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.BinanceSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.CaptureSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.CategoriesAndPeopleScreen
import com.xemmy.mmbank.ui.screens.settings.DemoSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.DiagnosticsSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.ImportMessagesSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.NotificationsSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.PreferencesSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.SecuritySettingsScreen
import com.xemmy.mmbank.ui.screens.settings.SettingsScreen
import com.xemmy.mmbank.ui.screens.splits.GroupDetailScreen
import com.xemmy.mmbank.ui.screens.splits.SplitsScreen
import com.xemmy.mmbank.ui.screens.transactions.TransactionsScreen
import com.xemmy.mmbank.ui.theme.MMbankTheme
import com.xemmy.mmbank.ui.theme.ambientBackground
import android.os.Build
import kotlinx.coroutines.delay
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.xemmy.mmbank.ui.util.SecureScreenPolicy

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires
// a fragment host; it is a ComponentActivity subclass, so setContent and the
// rest of the Compose wiring are unaffected.
class MainActivity : FragmentActivity() {

    companion object {
        /** Set by the Daily Budget Glance widget's shortcut button — see DailyBudgetWidget.kt. */
        const val EXTRA_OPEN_UNCATEGORIZED = "open_uncategorized"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 forces edge-to-edge at targetSdk 35; opt in explicitly so
        // API <35 matches. The theme is dark-pinned (Phase 0.1), so light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as MMBankApp).container
        val biometricAuth = BiometricAuthManagerImpl(this)
        val openUncategorizedQueue = intent?.getBooleanExtra(EXTRA_OPEN_UNCATEGORIZED, false) == true
        // Before the first frame, so not even the first render can be captured.
        applySecureScreen(container.secureScreen.value)
        setContent {
            val themeMode by container.themeMode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.resolve(isSystemInDarkTheme())
            // Keep status/nav-bar icon appearance in sync with the theme.
            DisposableEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (darkTheme) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                )
                onDispose {}
            }
            val secureScreen by container.secureScreen.collectAsStateWithLifecycle()
            LaunchedEffect(secureScreen) { applySecureScreen(secureScreen) }
            // The ledger's own one-off notices ("... balance was kept"), whichever screen is open.
            LaunchedEffect(Unit) {
                container.ledgerNotices.collect { notice ->
                    android.widget.Toast.makeText(this@MainActivity, notice, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            MMbankTheme(darkTheme = darkTheme) {
                val locked by container.appLock.locked.collectAsStateWithLifecycle()
                val backgroundStyle by container.backgroundPreset.collectAsStateWithLifecycle()
                Box {
                    // While locked, hide the ledger from TalkBack as well as from touch.
                    Box(if (locked) Modifier.clearAndSetSemantics {} else Modifier) {
                    MMbankApp(openUncategorizedQueue = openUncategorizedQueue, backgroundStyle = backgroundStyle)
                    }
                    // Opaque gate over (not instead of) the app so navigation
                    // and screen state survive a lock/unlock round-trip.
                    if (locked) {
                        LockScreen(
                            authenticate = {
                                biometricAuth.promptAuth(
                                    title = "Unlock MMbank",
                                    subtitle = "Confirm it's you to open your ledger",
                                )
                            },
                            onUnlocked = { container.appLock.onUnlocked() },
                            biometricAvailable = BiometricAuthManagerImpl.canAuthenticate(this@MainActivity),
                            pinAvailable = container.hasAppPin,
                            checkPin = { pin -> container.checkAppPin(pin) },
                        )
                    }
                }
            }
        }
    }

    /**
     * `FLAG_SECURE` app-wide while "Block screenshots" is on: no screenshots
     * or screen recording, and a blank thumbnail in Recents.
     */
    private fun applySecureScreen(blockScreenshots: Boolean) {
        val decision = SecureScreenPolicy.decide(
            blockScreenshots = blockScreenshots,
            appLockEnabled = (application as MMBankApp).container.preferences.appLockEnabled,
            sdkInt = Build.VERSION.SDK_INT,
        )
        if (decision.flagSecure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!decision.hideRecentsScreenshot)
    }

    override fun onStop() {
        // App lock may have been toggled since launch; re-evaluate before Recents snapshots us.
        applySecureScreen((application as MMBankApp).container.secureScreen.value)
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Defers the idle-timeout lock; a no-op while the lock is off/engaged.
        (application as MMBankApp).container.appLock.onUserInteraction()
    }
}

private data class TabVisual(val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

private fun AppTab.visual(): TabVisual = when (this) {
    AppTab.HOME -> TabVisual(Icons.Filled.Home, Icons.Outlined.Home)
    AppTab.ACTIVITY -> TabVisual(Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong)
    AppTab.INSIGHTS -> TabVisual(Icons.Filled.Insights, Icons.Outlined.Insights)
    AppTab.ACCOUNTS -> TabVisual(Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet)
    AppTab.PLAN -> TabVisual(Icons.Filled.Checklist, Icons.Outlined.Checklist)
}

// Five destinations, Material 3's sweet spot. Route ownership (which tab stays
// highlighted for child screens) lives in ui/navigation/AppNavigation.kt.

@Composable
fun MMbankApp(openUncategorizedQueue: Boolean = false, backgroundStyle: BackgroundStyle = BackgroundStyle.MINIMAL_SLATE) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Crash-loop breaker: if the app crashed on its last run, Home is the
    // prime suspect (it's whatever loaded first). Skip straight to Settings
    // instead — where Diagnostics, Backup/Export, and account editing are
    // still reachable — rather than repeating the same crash on every launch.
    val recoveredFromCrash = remember { Diagnostics.crashStreak(context) > 0 }
    val startDestination = TabRouting.startRoute(recoveredFromCrash)

    LaunchedEffect(Unit) {
        // Only clear the streak after staying up a while — a launch that
        // crashes again within this window keeps the counter alive so the
        // next launch also opens Settings, instead of alternating.
        delay(4_000)
        Diagnostics.clearCrashStreak(context)
    }

    // Daily Budget widget's "Review uncategorized" shortcut (see MainActivity.EXTRA_OPEN_UNCATEGORIZED).
    LaunchedEffect(openUncategorizedQueue) {
        if (openUncategorizedQueue) navController.navigate(Routes.UNCATEGORIZED_QUEUE)
    }

    val chrome = AdaptiveLayout.chrome(LocalConfiguration.current.screenWidthDp)
    val goToTab: (AppTab) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().ambientBackground(backgroundStyle),
        containerColor = Color.Transparent,
        bottomBar = {
            if (chrome == NavChrome.BOTTOM_BAR) {
                NavigationBar {
                    val currentTab = TabRouting.tabFor(currentRoute)
                    AppTab.values().forEach { dest ->
                        val visual = dest.visual()
                        val selected = currentTab == dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { goToTab(dest) },
                            icon = {
                                Icon(
                                    if (selected) visual.selectedIcon else visual.unselectedIcon,
                                    contentDescription = dest.label,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            label = {
                                Text(dest.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            },
                            // Pinned explicitly (Material3 1.4 changed the selected-label
                            // default to secondary) so the bottom bar's look is unaffected
                            // by the Compose BOM bump, in both Classic and Premium.
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
      Row(
        Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .fillMaxSize(),
      ) {
        if (chrome == NavChrome.RAIL) {
            NavigationRail {
                val currentTab = TabRouting.tabFor(currentRoute)
                AppTab.values().forEach { dest ->
                    val visual = dest.visual()
                    val selected = currentTab == dest
                    NavigationRailItem(
                        selected = selected,
                        onClick = { goToTab(dest) },
                        icon = {
                            Icon(
                                if (selected) visual.selectedIcon else visual.unselectedIcon,
                                contentDescription = dest.label,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text(dest.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = AdaptiveLayout.CONTENT_MAX_WIDTH_DP.dp)
                .fillMaxWidth(),
        ) {
            if (recoveredFromCrash) {
                Card(
                    Modifier.padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "The app crashed last time it opened, so Home was skipped this launch. " +
                            "Check Diagnostics below and share the log so this can be fixed for good.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            // Settings lives behind the gear on every tab root, not in the bar.
            if (NavTransitions.isTabRoot(currentRoute)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f),
            )
        }
        }
      }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    fun AnimatedContentTransitionScope<NavBackStackEntry>.kindFor(isPop: Boolean) =
        NavTransitions.kind(
            initialState.destination.route,
            targetState.destination.route,
            isPop,
            reduceMotion,
        )
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            when (kindFor(false)) {
                NavTransitionKind.TAB_SWITCH -> fadeIn(tween(NavTransitions.FADE_MILLIS))
                NavTransitionKind.FORWARD -> slideInHorizontally(tween(NavTransitions.SLIDE_MILLIS)) { it / 4 } +
                    fadeIn(tween(NavTransitions.SLIDE_MILLIS))
                else -> EnterTransition.None
            }
        },
        exitTransition = {
            when (kindFor(false)) {
                NavTransitionKind.TAB_SWITCH -> fadeOut(tween(NavTransitions.FADE_MILLIS))
                NavTransitionKind.FORWARD -> fadeOut(tween(NavTransitions.SLIDE_MILLIS))
                else -> ExitTransition.None
            }
        },
        popEnterTransition = {
            when (kindFor(true)) {
                NavTransitionKind.TAB_SWITCH -> fadeIn(tween(NavTransitions.FADE_MILLIS))
                NavTransitionKind.BACK -> fadeIn(tween(NavTransitions.SLIDE_MILLIS))
                else -> EnterTransition.None
            }
        },
        popExitTransition = {
            when (kindFor(true)) {
                NavTransitionKind.TAB_SWITCH -> fadeOut(tween(NavTransitions.FADE_MILLIS))
                NavTransitionKind.BACK -> slideOutHorizontally(tween(NavTransitions.SLIDE_MILLIS)) { it / 4 } +
                    fadeOut(tween(NavTransitions.SLIDE_MILLIS))
                else -> ExitTransition.None
            }
        },
    ) {
        composable(Routes.HOME) {
            DashboardScreen(
                onOpenReview = { navController.navigate("review_transactions") },
                onOpenUnassigned = { navController.navigate(Routes.ACTIVITY_UNASSIGNED) },
                onOpenCaptureSettings = { navController.navigate("settings_capture") },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenBackup = { navController.navigate("settings_backup") },
            )
        }
        // The optional args let Home and Accounts deep-link straight into the
        // "Unassigned" account filter, and a Copilot answer into exactly the
        // rows it describes (an encoded ActivityDrillDown). Query args are
        // optional and matched by name, so the bottom bar's plain
        // navigate("transactions") still matches this route.
        composable(
            route = Routes.ACTIVITY_PATTERN,
            arguments = listOf(
                navArgument("accountFilter") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("drill") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            TransactionsScreen(
                initialAccountFilter = entry.arguments?.getString("accountFilter").orEmpty(),
                initialDrill = entry.arguments?.getString("drill").orEmpty(),
                onOpenUncategorizedQueue = { navController.navigate(Routes.UNCATEGORIZED_QUEUE) },
                onOpenMerchant = { merchant -> navController.navigate(Routes.merchantDetail(Uri.encode(merchant))) },
            )
        }
        // The old standalone Uncategorized queue was merged into the Review inbox;
        // the route stays so the Activity banner, the widget and notifications still land somewhere.
        composable(Routes.UNCATEGORIZED_QUEUE) {
            ReviewTransactionsScreen(onBack = { navController.popBackStack() })
        }
        composable("review_transactions") {
            ReviewTransactionsScreen(onBack = { navController.popBackStack() })
        }
        composable("integrity_check") {
            IntegrityScreen(
                onBack = { navController.popBackStack() },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenUnassigned = { navController.navigate(Routes.ACTIVITY_UNASSIGNED) },
                onOpenStatement = { navController.navigate(Routes.STATEMENT_RECONCILE) },
                onOpenDrift = { accountId -> navController.navigate(Routes.accountHistory(accountId, drift = true)) },
            )
        }
        composable("insights") {
            InsightsScreen(
                onOpenRewards = { navController.navigate("cashback") },
                onOpenSubscriptions = { navController.navigate("subscriptions") },
                onOpenAnalytics = { navController.navigate("analytics") },
                onOpenGoals = { navController.navigate("goals") },
                onOpenBudgets = { navController.navigate("categories") },
                onOpenReport = { navController.navigate("monthly_report") },
                onOpenCopilot = { navController.navigate("copilot") },
                onOpenBills = { navController.navigate("bills") },
                onOpenSplits = { navController.navigate("splits") },
            )
        }
        composable(Routes.PLAN) {
            PlanScreen(onOpen = { route -> navController.navigate(route) })
        }
        composable("analytics") { AnalyticsScreen(onBack = { navController.popBackStack() }) }
        composable("accounts") {
            AccountsScreen(
                onOpenUnassigned = { navController.navigate(Routes.ACTIVITY_UNASSIGNED) },
                onOpenIntegrity = { navController.navigate("integrity_check") },
                onOpenDebtPlanner = { navController.navigate("debt_planner") },
                onOpenHistory = { accountId -> navController.navigate(Routes.accountHistory(accountId)) },
            )
        }
        composable(
            route = Routes.ACCOUNT_HISTORY_PATTERN,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("drift") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            AccountHistoryScreen(onBack = { navController.popBackStack() })
        }
        composable("debt_planner") { DebtPlannerScreen(onBack = { navController.popBackStack() }) }
        composable("cashback") { CashbackScreen() }
        composable("subscriptions") {
            SubscriptionsScreen(
                onBack = { navController.popBackStack() },
                onOpenMerchant = { merchant -> navController.navigate(Routes.merchantDetail(Uri.encode(merchant))) },
            )
        }
        composable("goals") { GoalsScreen(onBack = { navController.popBackStack() }) }
        composable("bills") { BillsScreen(onBack = { navController.popBackStack() }) }
        composable("splits") {
            SplitsScreen(
                onBack = { navController.popBackStack() },
                onOpenGroup = { groupId -> navController.navigate(Routes.splitsGroup(groupId)) },
            )
        }
        composable(
            route = Routes.SPLITS_GROUP_PATTERN,
            arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
        ) {
            GroupDetailScreen(onBack = { navController.popBackStack() })
        }
        composable("categories") {
            CategoriesAndPeopleScreen(onBack = { navController.popBackStack() }, initialTab = 0)
        }
        composable("monthly_report") {
            MonthlyReportScreen(
                onBack = { navController.popBackStack() },
                onOpenYear = { navController.navigate(Routes.YEAR_IN_REVIEW) },
            )
        }
        composable(Routes.UPCOMING) {
            UpcomingPaymentsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { route -> navController.navigate(route) },
            )
        }
        composable(Routes.YEAR_IN_REVIEW) { YearInReviewScreen(onBack = { navController.popBackStack() }) }
        composable(
            route = Routes.MERCHANT_DETAIL_PATTERN,
            arguments = listOf(navArgument("merchant") { type = NavType.StringType }),
        ) {
            MerchantDetailScreen(onBack = { navController.popBackStack() })
        }
        composable("copilot") {
            CopilotChatScreen(
                onBack = { navController.popBackStack() },
                onOpenActivity = { drill -> navController.navigate(Routes.activityDrill(Uri.encode(drill))) },
            )
        }
        composable(Routes.STATEMENT_RECONCILE) {
            StatementReconcileScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpen = { target ->
                    when (target) {
                        is SearchTarget.Merchant ->
                            navController.navigate(Routes.merchantDetail(Uri.encode(target.merchant)))
                        is SearchTarget.Rule -> navController.navigate(Routes.ruleEditor(target.ruleId))
                        is SearchTarget.Route ->
                            if (NavTransitions.isTabRoot(target.route)) {
                                navController.navigate(target.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            } else {
                                navController.navigate(target.route)
                            }
                    }
                },
            )
        }
        composable("rules") {
            RulesScreen(
                onEditRule = { id -> navController.navigate(Routes.ruleEditor(id)) },
                onNewRule = { navController.navigate(Routes.NEW_RULE) },
            )
        }
        composable(
            route = Routes.RULE_EDITOR_PATTERN,
            arguments = listOf(navArgument("ruleId") { type = NavType.LongType; defaultValue = -1L }),
        ) {
            RuleEditorScreen(onDone = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onOpen = { route -> navController.navigate(route) })
        }
        composable("settings_capture") { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_import") { ImportMessagesSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_notifications") { NotificationsSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_prefs") { PreferencesSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_categories_people") {
            CategoriesAndPeopleScreen(onBack = { navController.popBackStack() }, initialTab = 0)
        }
        composable("settings_binance") { BinanceSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_backup") { BackupSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_diagnostics") { DiagnosticsSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_security") { SecuritySettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_ai") { AiSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_appearance") { AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_demo") { DemoSettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
