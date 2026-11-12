package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** Minimum row heights from the plan: 56 / 64 / 72dp. */
enum class RowDensity(val minHeightDp: Int) {
    COMPACT(56),
    COMFORTABLE(64),
    SPACIOUS(72),
}

/** Picks the smallest density that fits the row's content. */
fun rowDensityFor(hasSubtitle: Boolean, hasLeading: Boolean): RowDensity = when {
    hasSubtitle && hasLeading -> RowDensity.SPACIOUS
    hasSubtitle || hasLeading -> RowDensity.COMFORTABLE
    else -> RowDensity.COMPACT
}

/**
 * Standard list row: optional leading, title, optional subtitle, optional trailing.
 * Tapping anywhere runs [onClick] when set.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    density: RowDensity = rowDensityFor(subtitle != null, leading != null),
    onClick: (() -> Unit)? = null,
) {
    ListRowLayout(
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = trailing,
        density = density,
        modifier = if (onClick != null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier,
    )
}

/** Row whose whole surface toggles a switch. */
@Composable
fun SwitchListRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    density: RowDensity = rowDensityFor(subtitle != null, leading != null),
) {
    ListRowLayout(
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = { Switch(checked = checked, onCheckedChange = null) },
        density = density,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** Row whose whole surface toggles a checkbox. */
@Composable
fun CheckboxListRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    density: RowDensity = rowDensityFor(subtitle != null, leading != null),
) {
    ListRowLayout(
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
        density = density,
        modifier = modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    )
}

/** Row whose whole surface selects a radio option. */
@Composable
fun RadioListRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    density: RowDensity = rowDensityFor(subtitle != null, leading != null),
) {
    ListRowLayout(
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = { RadioButton(selected = selected, onClick = null) },
        density = density,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun ListRowLayout(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    density: RowDensity,
    modifier: Modifier,
) {
    val colors = MMTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = density.minHeightDp.dp)
            .padding(horizontal = MMSpacing.gutter, vertical = MMSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MMSpacing.md),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}
