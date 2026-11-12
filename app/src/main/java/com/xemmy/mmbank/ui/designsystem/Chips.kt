package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** Selectable filter chip (checkbox semantics, at least 48dp touch target). */
@Composable
fun MMFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MMTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.textSecondary,
            selectedContainerColor = colors.surfaceRaised,
            selectedLabelColor = colors.textPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { role = Role.Checkbox },
    )
}

/** Action chip: runs something, has no selected state. */
@Composable
fun MMAssistChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MMTheme.colors
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.textPrimary,
        ),
        modifier = modifier.minimumInteractiveComponentSize(),
    )
}

/** Alpha of a tag's tinted background. */
const val TAG_CONTAINER_ALPHA = 0.16f

/** Non-interactive label such as "Pending" or "Transfer". */
@Composable
fun MMTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: MMTone? = null,
) {
    val colors = MMTheme.colors
    val accent = if (tone != null) colors.toneColor(tone) else colors.textSecondary
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = accent.copy(alpha = TAG_CONTAINER_ALPHA),
        contentColor = accent,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = MMSpacing.sm, vertical = MMSpacing.xs),
        )
    }
}
