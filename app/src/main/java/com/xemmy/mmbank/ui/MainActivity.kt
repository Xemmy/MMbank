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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import com.xemmy.mmbank.ui.screens.merchant.MerchantDetailScreen
import com.xemmy.mmbank.ui.screens.analytics.AnalyticsScreen
import com.xemmy.mmbank.ui.screens.review.ReviewTransactionsScreen
import com.xemmy.mmbank.ui.screens.rules.RuleEditorScreen
import com.xemmy.mmbank.ui.screens.rules.RulesScreen
import com.xemmy.mmbank.ui.screens.subscriptions.SubscriptionsScreen
import com.xemmy.mmbank.ui.screens.settings.AiSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.AppearanceSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.BackupSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.BinanceSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.CaptureSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.CategoriesAndPeopleScreen
import com.xemmy.mmbank.ui.screens.settings.DemoSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.DesignSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.DiagnosticsSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.ImportMessagesSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.PreferencesSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.SecuritySettingsScreen
import com.xemmy.mmbank.ui.screens.settings.SettingsScreen
import com.xemmy.mmbank.ui.screens.splits.GroupDetailScreen
import com.xemmy.mmbank.ui.screens.splits.SplitsScreen
import com.xemmy.mmbank.ui.screens.transactions.TransactionsScreen
import com.xemmy.mmbank.ui.screens.transactions.UncategorizedQueueScreen
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
            val designMode by container.designMode.collectAsStateWithLifecycle()
            val secureScreen by container.secureScreen.collectAsStateWithLifecycle()
            LaunchedEffect(secureScreen) { applySecureScreen(secureScreen) }
            MMbankTheme(designMode = designMode, darkTheme = true) {
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

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    /** Non-tab routes that should keep this tab highlighted while open. */
    val childRoutePrefixes: List<String> = emptyList(),
)

/** Activity, pre-filtered to transactions that belong to no account. */
private const val UNASSIGNED_ACTIVITY_ROUTE = "transactions?accountFilter=unassigned"

// Five destinations, Material 3's sweet spot. Rules lives under Settings and
// Rewards under the Dashboard tab, so neither crowds the bar.
private val topLevelDestinations = listOf(
    TopLevelDestination("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(
        // The Activity route carries an optional accountFilter arg, so the
        // current route reads as "transactions?accountFilter={...}" when
        // deep-linked -- the prefix is what keeps the tab highlighted.
        "transactions", "Activity",
        Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong,
        childRoutePrefixes = listOf("transactions"),
    ),
    TopLevelDestination(
        "insights", "Dashboard", Icons.Filled.Insights, Icons.Outlined.Insights,
        childRoutePrefixes = listOf("cashback"),
    ),
    TopLevelDestination(
        "accounts", "Accounts",
        Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet,
        childRoutePrefixes = listOf("debt_planner"),
    ),
    TopLevelDestination(
        "settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings,
        childRoutePrefixes = listOf("settings_", "rules", "rule_editor"),
    ),
)

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
    val startDestination = if (recoveredFromCrash) "settings" else "home"

    LaunchedEffect(Unit) {
        // Only clear the streak after staying up a while — a launch that
        // crashes again within this window keeps the counter alive so the
        // next launch also opens Settings, instead of alternating.
        delay(4_000)
        Diagnostics.clearCrashStreak(context)
    }

    // Daily Budget widget's "Review uncategorized" shortcut (see MainActivity.EXTRA_OPEN_UNCATEGORIZED).
    LaunchedEffect(openUncategorizedQueue) {
        if (openUncategorizedQueue) navController.navigate("uncategorized_queue")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().ambientBackground(backgroundStyle),
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { dest ->
                    val selected = currentRoute == dest.route ||
                        dest.childRoutePrefixes.any { currentRoute?.startsWith(it) == true }
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = dest.label,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                dest.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
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
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .fillMaxSize(),
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
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable("home") {
            DashboardScreen(
                onOpenReview = { navController.navigate("review_transactions") },
                onOpenUnassigned = { navController.navigate(UNASSIGNED_ACTIVITY_ROUTE) },
                onOpenCaptureSettings = { navController.navigate("settings_capture") },
            )
        }
        // The optional args let Home and Accounts deep-link straight into the
        // "Unassigned" account filter, and a Copilot answer into exactly the
        // rows it describes (an encoded ActivityDrillDown). Query args are
        // optional and matched by name, so the bottom bar's plain
        // navigate("transactions") still matches this route.
        composable(
            route = "transactions?accountFilter={accountFilter}&drill={drill}",
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
                onOpenUncategorizedQueue = { navController.navigate("uncategorized_queue") },
                onOpenMerchant = { merchant -> navController.navigate("merchant_detail/${Uri.encode(merchant)}") },
            )
        }
        composable("uncategorized_queue") {
            UncategorizedQueueScreen(onBack = { navController.popBackStack() })
        }
        composable("review_transactions") {
            ReviewTransactionsScreen(onBack = { navController.popBackStack() })
        }
        composable("integrity_check") {
            IntegrityScreen(
                onBack = { navController.popBackStack() },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenUnassigned = { navController.navigate(UNASSIGNED_ACTIVITY_ROUTE) },
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
        composable("analytics") { AnalyticsScreen(onBack = { navController.popBackStack() }) }
        composable("accounts") {
            AccountsScreen(
                onOpenUnassigned = { navController.navigate(UNASSIGNED_ACTIVITY_ROUTE) },
                onOpenIntegrity = { navController.navigate("integrity_check") },
                onOpenDebtPlanner = { navController.navigate("debt_planner") },
            )
        }
        composable("debt_planner") { DebtPlannerScreen(onBack = { navController.popBackStack() }) }
        composable("cashback") { CashbackScreen() }
        composable("subscriptions") {
            SubscriptionsScreen(
                onBack = { navController.popBackStack() },
                onOpenMerchant = { merchant -> navController.navigate("merchant_detail/${Uri.encode(merchant)}") },
            )
        }
        composable("goals") { GoalsScreen(onBack = { navController.popBackStack() }) }
        composable("bills") { BillsScreen(onBack = { navController.popBackStack() }) }
        composable("splits") {
            SplitsScreen(
                onBack = { navController.popBackStack() },
                onOpenGroup = { groupId -> navController.navigate("splits_group/$groupId") },
            )
        }
        composable(
            route = "splits_group/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
        ) {
            GroupDetailScreen(onBack = { navController.popBackStack() })
        }
        composable("categories") {
            CategoriesAndPeopleScreen(onBack = { navController.popBackStack() }, initialTab = 0)
        }
        composable("monthly_report") { MonthlyReportScreen(onBack = { navController.popBackStack() }) }
        composable(
            route = "merchant_detail/{merchant}",
            arguments = listOf(navArgument("merchant") { type = NavType.StringType }),
        ) {
            MerchantDetailScreen(onBack = { navController.popBackStack() })
        }
        composable("copilot") {
            CopilotChatScreen(
                onBack = { navController.popBackStack() },
                onOpenActivity = { drill -> navController.navigate("transactions?drill=${Uri.encode(drill)}") },
            )
        }
        composable("rules") {
            RulesScreen(
                onEditRule = { id -> navController.navigate("rule_editor?ruleId=$id") },
                onNewRule = { navController.navigate("rule_editor?ruleId=-1") },
            )
        }
        composable(
            route = "rule_editor?ruleId={ruleId}",
            arguments = listOf(navArgument("ruleId") { type = NavType.LongType; defaultValue = -1L }),
        ) {
            RuleEditorScreen(onDone = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onOpen = { route -> navController.navigate(route) })
        }
        composable("settings_capture") { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_import") { ImportMessagesSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_prefs") { PreferencesSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_categories_people") {
            CategoriesAndPeopleScreen(onBack = { navController.popBackStack() }, initialTab = 0)
        }
        composable("settings_binance") { BinanceSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_backup") { BackupSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_diagnostics") { DiagnosticsSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_security") { SecuritySettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_ai") { AiSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_design") { DesignSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_appearance") { AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_demo") { DemoSettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
