package com.xemmy.mmbank.ui

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.xemmy.mmbank.ui.auth.BiometricAuthManagerImpl
import com.xemmy.mmbank.ui.auth.LockScreen
import com.xemmy.mmbank.ui.screens.accounts.AccountsScreen
import com.xemmy.mmbank.ui.screens.cashback.CashbackScreen
import com.xemmy.mmbank.ui.screens.dashboard.DashboardScreen
import com.xemmy.mmbank.ui.screens.insights.InsightsScreen
import com.xemmy.mmbank.ui.screens.rules.RuleEditorScreen
import com.xemmy.mmbank.ui.screens.rules.RulesScreen
import com.xemmy.mmbank.ui.screens.settings.BackupSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.BinanceSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.CaptureSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.DiagnosticsSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.PreferencesSettingsScreen
import com.xemmy.mmbank.ui.screens.settings.SettingsScreen
import com.xemmy.mmbank.ui.screens.transactions.TransactionsScreen
import com.xemmy.mmbank.ui.theme.MMbankTheme
import kotlinx.coroutines.delay

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires
// a fragment host; it is a ComponentActivity subclass, so setContent and the
// rest of the Compose wiring are unaffected.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MMBankApp).container
        val biometricAuth = BiometricAuthManagerImpl(this)
        setContent {
            MMbankTheme {
                val locked by container.appLock.locked.collectAsStateWithLifecycle()
                Box {
                    MMbankApp()
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
                        )
                    }
                }
            }
        }
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

// Five destinations, Material 3's sweet spot. Rules lives under Settings and
// Rewards under the Dashboard tab, so neither crowds the bar.
private val topLevelDestinations = listOf(
    TopLevelDestination("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(
        "transactions", "Activity",
        Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong,
    ),
    TopLevelDestination(
        "insights", "Dashboard", Icons.Filled.Insights, Icons.Outlined.Insights,
        childRoutePrefixes = listOf("cashback"),
    ),
    TopLevelDestination(
        "accounts", "Accounts",
        Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet,
    ),
    TopLevelDestination(
        "settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings,
        childRoutePrefixes = listOf("settings_", "rules", "rule_editor"),
    ),
)

@Composable
fun MMbankApp() {
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

    Scaffold(
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
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
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
        composable("home") { DashboardScreen() }
        composable("transactions") { TransactionsScreen() }
        composable("insights") {
            InsightsScreen(onOpenRewards = { navController.navigate("cashback") })
        }
        composable("accounts") { AccountsScreen() }
        composable("cashback") { CashbackScreen() }
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
        composable("settings_prefs") { PreferencesSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_binance") { BinanceSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_backup") { BackupSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("settings_diagnostics") { DiagnosticsSettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
