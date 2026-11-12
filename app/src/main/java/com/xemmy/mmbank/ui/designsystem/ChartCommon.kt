package com.xemmy.mmbank.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.xemmy.mmbank.ui.theme.MMColors
import com.xemmy.mmbank.ui.theme.MMTheme

/**
 * Categorical series colours for charts, drawn from the semantic tokens until
 * dedicated chart tokens exist (Improvement Plan §1.1). Hues alternate so
 * neighbouring slices differ; charts never rely on these alone, since every
 * series also has a text label, a legend row or a readout.
 */
fun MMColors.chartSeries(): List<Color> = listOf(
    accent,
    moneyIn,
    warning,
    info,
    danger,
    brandTeal,
    brandGold,
    textSecondary,
)

/** Colour for series [index], cycling when there are more series than colours. */
fun MMColors.chartSeriesColor(index: Int): Color {
    val palette = chartSeries()
    return palette[((index % palette.size) + palette.size) % palette.size]
}

/** Replaces a chart's drawing semantics with one spoken [summary]. */
internal fun Modifier.chartSummary(summary: String): Modifier =
    clearAndSetSemantics { contentDescription = summary }

/** One-line readout under a chart: the tapped value, or a hint. Announced politely when it changes. */
@Composable
internal fun ChartReadout(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium,
        color = MMTheme.colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
