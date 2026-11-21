package com.xemmy.mmbank.ui.screens.review.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.parser.CategorySuggestion

/**
 * Top AI-recommended category chips (up to 3, from [com.xemmy.mmbank.domain.parser.CategorySuggestionEngine])
 * plus a "More Categories…" chip opening the searchable picker sheet. A
 * single tap on a suggestion assigns it and, via the reactive uncategorized
 * queue, removes the row from the review list.
 */
@Composable
fun CategorySuggestionRow(
    suggestions: List<CategorySuggestion>,
    onSelect: (CategorySuggestion) -> Unit,
    onMoreCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            AssistChip(
                onClick = { onSelect(suggestion) },
                label = { Text("${suggestion.icon} ${suggestion.name}") },
            )
        }
        AssistChip(
            onClick = onMoreCategories,
            label = { Text("More categories…") },
            leadingIcon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) },
        )
    }
}
