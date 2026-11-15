package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.ui.theme.MMTheme
import java.time.LocalDate

/**
 * The one period control: W · M · Y · Max, a ‹ label › stepper and "…" for Last 3/6/12 months and
 * a custom range. Stateless: the caller owns the [PeriodWindow].
 */
@Composable
fun PeriodBar(
    window: PeriodWindow,
    today: LocalDate,
    onUnit: (PeriodUnit) -> Unit,
    onWindow: (PeriodWindow) -> Unit,
    onPickCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("W" to PeriodUnit.WEEK, "M" to PeriodUnit.MONTH, "Y" to PeriodUnit.YEAR, "Max" to PeriodUnit.ALL)
                .forEach { (label, unit) ->
                    MMFilterChip(label = label, selected = window.unit == unit, onClick = { onUnit(unit) })
                }
            MMFilterChip(
                label = "More",
                selected = window.unit == PeriodUnit.CUSTOM,
                onClick = { moreOpen = true },
                leadingIcon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) },
            )
        }
        if (window.unit != PeriodUnit.ALL) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onWindow(window.previous()) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
                }
                Text(
                    window.label(today),
                    style = MaterialTheme.typography.titleSmall,
                    color = MMTheme.colors.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Period: ${window.label(today)}" },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { onWindow(window.next()) }, enabled = window.canGoNext(today)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
                }
            }
        }
    }
    if (moreOpen) {
        MMBottomSheet(onDismiss = { moreOpen = false }) {
            val weekStart = window.weekStart
            listOf(3, 6, 12).forEach { n ->
                ListRow(
                    title = "Last $n months",
                    onClick = {
                        onWindow(PeriodWindow.lastMonths(n, today, weekStart))
                        moreOpen = false
                    },
                )
            }
            ListRow(
                title = "Custom range…",
                onClick = {
                    moreOpen = false
                    onPickCustom()
                },
            )
        }
    }
}
