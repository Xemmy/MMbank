package com.xemmy.mmbank.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.xemmy.mmbank.ui.screens.accounts.AccountsScreen
import com.xemmy.mmbank.ui.screens.cashback.CashbackScreen
import com.xemmy.mmbank.ui.screens.dashboard.DashboardScreen
import com.xemmy.mmbank.ui.screens.rules.RuleEditorScreen
import com.xemmy.mmbank.ui.screens.rules.RulesScreen
import com.xemmy.mmbank.ui.screens.settings.SettingsScreen
import com.xemmy.mmbank.ui.screens.transactions.TransactionsScreen
import com.xemmy.mmbank.ui.theme.MMbankTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MMbankTheme {
                MMbankApp()
            }
        }
    }
}

private data class TopLevelDestination(val route: String, val label: String, val icon: ImageVector)

private val topLevelDestinations = listOf(
    TopLevelDestination("dashboard", "Home", Icons.Filled.Dashboard),
    TopLevelDestination("transactions", "Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    TopLevelDestination("accounts", "Accounts", Icons.Filled.AccountBalanceWallet),
    TopLevelDestination("cashback", "Rewards", Icons.Filled.Redeem),
    TopLevelDestination("rules", "Rules", Icons.Filled.Tune),
    TopLevelDestination("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun MMbankApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(20.dp)) },
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
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("dashboard") { DashboardScreen() }
            composable("transactions") { TransactionsScreen() }
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
            composable("settings") { SettingsScreen() }
        }
    }
}
