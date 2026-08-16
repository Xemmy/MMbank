package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.Category
import java.math.BigDecimal

/** Hoisted state for [CategoryEditorFields] -- shared by [CategoryEditorDialog] and [CategoryPickerBottomSheet]'s create form. */
data class CategoryEditorFormState(
    val name: String = "",
    val icon: String = PRESET_ICONS.first(),
    val colorHex: String = PRESET_COLORS.first(),
    val monthlyCapText: String = "",
    val parent: Category? = null,
)

/** Blank name, or a non-blank cap that fails to parse as a number. */
fun CategoryEditorFormState.isValid(): Boolean {
    val cap = monthlyCapText.trim()
    val capValid = cap.isBlank() || cap.toBigDecimalOrNull() != null
    return name.isNotBlank() && capValid
}

/** Parses [CategoryEditorFormState.monthlyCapText]; null when blank or unparseable. */
fun CategoryEditorFormState.capValue(): BigDecimal? =
    monthlyCapText.trim().takeIf { it.isNotBlank() }?.toBigDecimalOrNull()

/**
 * The full category name/icon/color/cap/parent form, as a group of fields
 * over hoisted [state] -- embeddable directly in any dialog's `text` slot.
 */
@Composable
fun CategoryEditorFields(
    state: CategoryEditorFormState,
    onStateChange: (CategoryEditorFormState) -> Unit,
    currency: String,
    /** Categories this one can be filed under. */
    candidateParents: List<Category>,
) {
    var parentMenuOpen by remember { mutableStateOf(false) }
    val capValue = state.monthlyCapText.trim().takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    val capValid = state.monthlyCapText.isBlank() || capValue != null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onStateChange(state.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Icon", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_ICONS.forEach { emoji ->
                val selected = emoji == state.icon
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onStateChange(state.copy(icon = emoji)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.icon,
            onValueChange = { onStateChange(state.copy(icon = it)) },
            label = { Text("Or type any emoji") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Color", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_COLORS.forEach { hex ->
                val selected = hex == state.colorHex
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(categoryColor(hex))
                        .clickable { onStateChange(state.copy(colorHex = hex)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.monthlyCapText,
            onValueChange = { input -> if (input.all { it.isDigit() || it == '.' }) onStateChange(state.copy(monthlyCapText = input)) },
            label = { Text("Monthly cap ($currency, optional)") },
            supportingText = { Text("Leave blank for no budget. Alerts at 80% and again if you pass it.") },
            singleLine = true,
            isError = !capValid,
            modifier = Modifier.fillMaxWidth(),
        )

        Box {
            Card(Modifier.fillMaxWidth().clickable { parentMenuOpen = true }) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.parent?.let { "Parent: ${it.icon} ${it.name}" } ?: "No parent (top-level)",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose parent category")
                }
            }
            DropdownMenu(expanded = parentMenuOpen, onDismissRequest = { parentMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No parent (top-level)") },
                    onClick = { onStateChange(state.copy(parent = null)); parentMenuOpen = false },
                )
                candidateParents.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${option.icon} ${option.name}") },
                        onClick = { onStateChange(state.copy(parent = option)); parentMenuOpen = false },
                    )
                }
            }
        }
    }
}
