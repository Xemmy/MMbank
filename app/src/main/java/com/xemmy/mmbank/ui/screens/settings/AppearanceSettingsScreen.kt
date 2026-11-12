package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import com.xemmy.mmbank.domain.model.ThemeMode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.BackgroundStyle
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.MMShapes
import com.xemmy.mmbank.ui.theme.ambientBackground
import com.xemmy.mmbank.ui.theme.description
import com.xemmy.mmbank.ui.theme.displayName

/**
 * Appearance settings: pick the ambient background style drawn behind the
 * whole app. Each card's mini preview is drawn with the same
 * [ambientBackground] modifier the app itself uses, so it's a true preview,
 * and selecting a card applies it live everywhere immediately.
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val current by viewModel.backgroundStyle.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    SettingsSubpage(title = "Appearance & Backgrounds", onBack = onBack) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = mode == themeMode, role = Role.RadioButton, onClick = { viewModel.setThemeMode(mode) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = mode == themeMode, onClick = null)
                    Text(
                        when (mode) {
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.LIGHT -> "Light (beta)"
                            ThemeMode.SYSTEM -> "Follow system"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Text(
            "Light is a beta: screens move over to it one by one, so some may still look dark.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "An ultra-subtle ambient glow behind the app. Pick a style below — it applies instantly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackgroundStyle.entries.forEach { style ->
                BackgroundStyleCard(
                    style = style,
                    selected = style == current,
                    onSelect = { viewModel.setBackgroundStyle(style) },
                )
            }
        }
    }
}

@Composable
private fun BackgroundStyleCard(
    style: BackgroundStyle,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MMShapes.extraSmall)
                    .ambientBackground(style),
            )
            Column(Modifier.weight(1f)) {
                Text(style.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
