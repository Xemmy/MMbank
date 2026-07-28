package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.Category
import java.math.BigDecimal

/** Preset swatches offered when creating a category -- distinct, legible on both themes. */
private val PRESET_COLORS = listOf(
    "#4CAF50", "#FF7043", "#29B6F6", "#FFCA28", "#AB47BC",
    "#EC407A", "#26A69A", "#66BB6A", "#8D6E63", "#78909C",
)

/** Best-effort "#RRGGBB" -> [Color]; falls back to a neutral grey on a malformed hex. */
fun categoryColor(hex: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF9E9E9E))

/**
 * Category picker + inline "+ New Category" creation. Built as an [AlertDialog]
 * rather than a `ModalBottomSheet` -- see [com.xemmy.mmbank.ui.screens.transactions.TransactionDetailSheet]'s
 * doc comment: a `ModalBottomSheet` in this app has previously rendered as a
 * translucent, then empty, panel with no backdrop blur on some devices, which
 * is exactly why every other overlay here is a real dialog window instead.
 */
@Composable
fun CategoryPickerBottomSheet(
    categories: List<Category>,
    onSelect: (Category) -> Unit,
    onCreateNew: (name: String, colorHex: String, icon: String, monthlyCap: BigDecimal?) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(PRESET_COLORS.first()) }
    var newCap by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val filteredCategories = remember(categories, query) {
        if (query.isBlank()) categories else categories.filter { it.name.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New category" else "Choose a category") },
        text = {
            if (creating) {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Color", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESET_COLORS.forEach { hex ->
                            val selected = hex == newColor
                            Column(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(categoryColor(hex))
                                    .clickable { newColor = hex },
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.padding(6.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newCap,
                        onValueChange = { input -> if (input.all { it.isDigit() || it == '.' }) newCap = input },
                        label = { Text("Monthly cap (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search categories") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                    if (filteredCategories.isEmpty()) {
                        Text(
                            "No categories match \"$query\"",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    LazyColumn {
                        items(filteredCategories, key = { it.id }) { category ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(category) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(categoryColor(category.colorHex)),
                                ) {}
                                Spacer(Modifier.width(12.dp))
                                Text("${category.icon}  ${category.name}", modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateNew(trimmed, newColor, "🏷️", newCap.toBigDecimalOrNull())
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Create") }
            } else {
                TextButton(onClick = {
                    newName = query.trim()
                    creating = true
                }) {
                    Text("+ New Category", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (creating) creating = false else onDismiss() }) {
                Text(if (creating) "Back" else "Cancel")
            }
        },
    )
}
