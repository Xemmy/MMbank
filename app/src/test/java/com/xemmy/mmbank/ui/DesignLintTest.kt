package com.xemmy.mmbank.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-drift ratchet (Improvement Plan §8.2). Counts raw regex hits per
 * file under `ui/` and compares them with the baselines below. A count that
 * goes UP (or a new file that starts violating) fails, so drift can't grow;
 * a count that goes DOWN also fails until the baseline is lowered, so the
 * baselines only ever tighten as screens migrate to the design system.
 * "No TextStyle under 11sp" is pinned separately in TypographyTest.
 */
class DesignLintTest {

    private data class Rule(
        val name: String,
        val regex: Regex,
        val inScope: (String) -> Boolean,
        val baseline: Map<String, Int>,
    )

    private val rules = listOf(
        Rule(
            "COLOR_LITERAL",
            Regex("Color\\(0x"),
            { !it.startsWith("ui/theme/") && !it.startsWith("ui/designsystem/") },
            mapOf(
                "ui/components/AccountCarousel.kt" to 3,
                "ui/components/CategoryPresets.kt" to 1,
                "ui/components/GlassWalletCard.kt" to 16,
                "ui/screens/analytics/SpendingHeatmap.kt" to 5,
                "ui/screens/transactions/TransactionsScreen.kt" to 2,
                "ui/widgets/DailyBudgetWidget.kt" to 3,
            ),
        ),
        Rule(
            "FONT_SIZE",
            Regex("fontSize\\s*="),
            { !it.startsWith("ui/theme/") },
            mapOf(
                "ui/screens/settings/BackupDiagnosticsSettings.kt" to 1,
                "ui/screens/transactions/HeroSpendSummaryCard.kt" to 7,
                "ui/screens/transactions/TransactionDetailSheet.kt" to 1,
                "ui/screens/transactions/TransactionEditorDialogs.kt" to 1,
                "ui/screens/transactions/TransactionsScreen.kt" to 9,
                "ui/util/AmountText.kt" to 1,
            ),
        ),
        Rule(
            "OBSIDIAN_TOKENS",
            Regex("ObsidianTokens\\."),
            { true },
            mapOf(
                "ui/auth/LockScreen.kt" to 5,
                "ui/auth/PinPad.kt" to 4,
                "ui/components/CanvasCharts.kt" to 5,
                "ui/components/DonutChart.kt" to 2,
                "ui/screens/transactions/HeroSpendSummaryCard.kt" to 22,
                "ui/screens/transactions/MoreFiltersDialog.kt" to 6,
                "ui/screens/transactions/TransactionDetailSheet.kt" to 10,
                "ui/screens/transactions/TransactionEditorDialogs.kt" to 7,
                "ui/screens/transactions/TransactionsScreen.kt" to 45,
                "ui/theme/Glass.kt" to 5,
                "ui/theme/Glow.kt" to 3,
                "ui/theme/ObsidianTokens.kt" to 6,
                "ui/theme/RowComponents.kt" to 7,
                "ui/theme/Theme.kt" to 5,
                "ui/theme/Typography.kt" to 10,
                "ui/util/AmountText.kt" to 2,
            ),
        ),
        Rule(
            "AS_MONEY_IN_SCREENS",
            Regex("asMoney\\w*\\("),
            { it.startsWith("ui/screens/") },
            mapOf(
                "ui/screens/analytics/AnalyticsScreen.kt" to 2,
                "ui/screens/insights/InsightsScreen.kt" to 8,
                "ui/screens/settings/SettingsViewModel.kt" to 1,
                "ui/screens/transactions/HeroSpendSummaryCard.kt" to 4,
                "ui/screens/transactions/TransactionDetailSheet.kt" to 2,
                "ui/screens/transactions/TransactionsScreen.kt" to 1,
                "ui/screens/transactions/UncategorizedQueueScreen.kt" to 1,
            ),
        ),
        Rule(
            "ROUNDED_LITERAL",
            Regex("RoundedCornerShape\\(\\s*\\d+(\\.\\d+)?\\.dp\\s*\\)"),
            { true },
            mapOf(
                "ui/components/AccountCarousel.kt" to 1,
                "ui/components/GlassWalletCard.kt" to 1,
                "ui/screens/analytics/SpendingHeatmap.kt" to 2,
                "ui/screens/settings/AppearanceSettingsScreen.kt" to 1,
                "ui/screens/transactions/HeroSpendSummaryCard.kt" to 1,
                "ui/screens/transactions/TransactionsScreen.kt" to 3,
            ),
        ),
    )

    private fun uiRoot(): File =
        listOf("src/main/java/com/xemmy/mmbank", "app/src/main/java/com/xemmy/mmbank")
            .map(::File).first { it.isDirectory }

    private fun actual(rule: Rule, root: File): Map<String, Int> =
        File(root, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (rel, _) -> rule.inScope(rel) }
            .map { (rel, f) -> rel to rule.regex.findAll(f.readText()).count() }
            .filter { it.second > 0 }
            .toMap()

    @Test
    fun `design drift never grows and baselines stay tight`() {
        val root = uiRoot()
        val problems = mutableListOf<String>()
        for (rule in rules) {
            val now = actual(rule, root)
            for (file in (now.keys + rule.baseline.keys).toSortedSet()) {
                val have = now[file] ?: 0
                val allowed = rule.baseline[file] ?: 0
                when {
                    have > allowed -> problems += "${rule.name}: $file has $have (allowed $allowed) - use design tokens/components"
                    have < allowed -> problems += "${rule.name}: $file is down to $have - lower its baseline from $allowed"
                }
            }
        }
        assertTrue("Design lint:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
