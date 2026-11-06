package com.xemmy.mmbank.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xemmy.mmbank.domain.model.Category
import java.math.BigDecimal

/**
 * Full create/edit form for a [Category], as a standalone dialog. Null
 * [initial] means create (fields start blank/defaulted); non-null means edit
 * (fields seeded from it).
 */
@Composable
fun CategoryEditorDialog(
    initial: Category?,
    currency: String,
    /** Every known category, for computing which ones are valid parents. */
    allCategories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, colorHex: String, monthlyCap: BigDecimal?, parentCategoryId: Long?) -> Unit,
) {
    // Excludes itself and any category that has it as a parent -- self-reparenting would create a cycle.
    val candidateParents = remember(allCategories, initial) {
        if (initial == null) allCategories else allCategories.filter { it.id != initial.id && it.parentCategoryId != initial.id }
    }

    var state by remember(initial) {
        mutableStateOf(
            CategoryEditorFormState(
                name = initial?.name.orEmpty(),
                icon = initial?.icon ?: PRESET_ICONS.first(),
                colorHex = initial?.colorHex ?: PRESET_COLORS.first(),
                monthlyCapText = initial?.monthlyCap?.toPlainString().orEmpty(),
                parent = candidateParents.find { it.id == initial?.parentCategoryId },
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category", style = MaterialTheme.typography.titleLarge) },
        text = {
            CategoryEditorFields(
                state = state,
                onStateChange = { state = it },
                currency = currency,
                candidateParents = candidateParents,
            )
        },
        confirmButton = {
            TextButton(
                enabled = state.isValid(),
                onClick = {
                    onSave(
                        state.name.trim(),
                        state.icon.trim().ifBlank { "🏷️" },
                        state.colorHex.trim(),
                        state.capValue(),
                        state.parent?.id,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
