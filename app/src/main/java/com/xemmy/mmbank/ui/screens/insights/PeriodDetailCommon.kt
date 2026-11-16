package com.xemmy.mmbank.ui.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.domain.report.BucketFlow
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.screens.dashboard.DashboardDateRange
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a detail page draws its numbers. */
enum class DetailChart(val label: String) { DONUT("Donut"), BARS("Bars"), LINE("Line") }

@Composable
internal fun ChartToggle(options: List<DetailChart>, selected: DetailChart, onSelect: (DetailChart) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { MMFilterChip(label = it.label, selected = it == selected, onClick = { onSelect(it) }) }
    }
}

/** The period bar plus the custom-range dialog, wired to a [PeriodDetailViewModel]. */
@Composable
internal fun DetailPeriodBar(viewModel: PeriodDetailViewModel) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    var customOpen by remember { mutableStateOf(false) }
    com.xemmy.mmbank.ui.designsystem.PeriodBar(
        window = period,
        today = viewModel.today,
        onUnit = viewModel::setUnit,
        onWindow = viewModel::setPeriod,
        onPickCustom = { customOpen = true },
    )
    if (customOpen) {
        DashboardCustomDateRangeDialog(
            initial = period.range()?.let { DashboardDateRange.Custom(it.start, it.end) },
            onDismiss = { customOpen = false },
            onSelect = { start, end ->
                viewModel.setPeriod(PeriodWindow.custom(start, end, viewModel.weekStartDay))
                customOpen = false
            },
        )
    }
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("d", Locale.US)
private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM", Locale.US)

/** Short axis label for a bucket: the day number for a one-day bucket, else the month. */
internal fun bucketLabel(window: DateWindow): String =
    if (window.start == window.end) window.start.format(DAY_LABEL) else window.start.format(MONTH_LABEL)

internal fun List<BucketFlow>.runningNet(): List<Double> {
    var sum = 0.0
    return map { sum += it.net; sum }
}

internal val PeriodUnit.isBounded: Boolean get() = this != PeriodUnit.ALL
