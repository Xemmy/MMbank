package com.xemmy.mmbank.ui.components

import androidx.compose.ui.graphics.Color

/** Preset swatches offered when creating a category -- distinct, legible on both themes. */
val PRESET_COLORS = listOf(
    "#4CAF50", "#FF7043", "#29B6F6", "#FFCA28", "#AB47BC",
    "#EC407A", "#26A69A", "#66BB6A", "#8D6E63", "#78909C",
)

/**
 * Curated icon grid offered when creating/editing a category -- the
 * [com.xemmy.mmbank.domain.model.SpendingCategory] emoji set, deduped. Not
 * exhaustive: [CategoryEditorFields] also renders a free-text field so any
 * emoji can be typed directly, since no system emoji-keyboard component
 * exists in this app to build a full picker.
 */
val PRESET_ICONS: List<String> = listOf(
    "🏷️", "🛒", "🍽️", "🚕", "⛽", "🛍️", "💡", "🎬", "🩺", "✈️", "🔁", "💰", "🏦", "•",
).distinct()

/** Best-effort "#RRGGBB" -> [Color]; falls back to a neutral grey on a malformed hex. */
fun categoryColor(hex: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF9E9E9E))
