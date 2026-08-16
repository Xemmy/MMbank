package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import com.xemmy.mmbank.domain.model.Category
import java.math.BigDecimal

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
    currency: String,
    onSelect: (Category) -> Unit,
    onCreateNew: (name: String, colorHex: String, icon: String, monthlyCap: BigDecimal?, parentCategoryId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(CategoryEditorFormState()) }
    var query by remember { mutableStateOf("") }
    val filteredCategories = remember(categories, query) {
        if (query.isBlank()) categories else categories.filter { it.name.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New category" else "Choose a category") },
        text = {
            if (creating) {
                CategoryEditorFields(
                    state = newCategory,
                    onStateChange = { newCategory = it },
                    currency = currency,
                    candidateParents = categories,
                )
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
                        val trimmed = newCategory.name.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateNew(
                                trimmed,
                                newCategory.colorHex,
                                newCategory.icon.trim().ifBlank { "🏷️" },
                                newCategory.capValue(),
                                newCategory.parent?.id,
                            )
                        }
                    },
                    enabled = newCategory.isValid(),
                ) { Text("Create") }
            } else {
                TextButton(onClick = {
                    newCategory = CategoryEditorFormState(name = query.trim())
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
