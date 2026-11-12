package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MMbankTheme

private fun previewMoney(v: Double): String = "AED " + v.toLong()

@Composable
private fun ChartsPreviewContent() {
    Column(
        Modifier.background(MMTheme.colors.canvas).padding(MMSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(MMSpacing.sectionGap),
    ) {
        MMCard {
            BarChart(
                entries = listOf(
                    BarEntry("Jan", 1200.0), BarEntry("Feb", 800.0), BarEntry("Mar", 2100.0),
                    BarEntry("Apr", 0.0), BarEntry("May", 1500.0),
                ),
                title = "Spend by month",
                valueLabel = ::previewMoney,
            )
        }
        MMCard {
            DonutChart(
                slices = listOf(
                    DonutSlice("Dining", 340.0), DonutSlice("Groceries", 410.0),
                    DonutSlice("Transport", 150.0), DonutSlice("Other", 100.0),
                ),
                title = "Spend by category",
                centerLabel = "AED 1,000",
                centerCaption = "Total",
                valueLabel = ::previewMoney,
            )
        }
        MMCard {
            Sparkline(
                values = listOf(10.0, 14.0, 9.0, 18.0, 22.0, 19.0, 27.0),
                title = "Daily spend",
                valueLabel = ::previewMoney,
            )
        }
        MMCard {
            Heatmap(
                cells = List(21) { HeatCell("Day ${it + 1}", if (it % 4 == 0) 0.0 else (it * 37 % 200).toDouble()) },
                title = "Spending by day",
                valueLabel = ::previewMoney,
            )
        }
        MMCard {
            PacingGauge(
                spent = 640.0,
                budget = 1000.0,
                elapsedFraction = 0.5f,
                title = "Dining budget",
                valueLabel = ::previewMoney,
            )
        }
    }
}

@MMPreviews
@Composable
private fun ChartsPreview() {
    MMbankTheme { ChartsPreviewContent() }
}

@MMPreviews
@Composable
private fun ChartsHiddenPreview() {
    MMbankTheme {
        CompositionLocalProvider(LocalHideBalances provides true) { ChartsPreviewContent() }
    }
}
