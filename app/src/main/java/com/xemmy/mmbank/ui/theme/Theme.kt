package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332E),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0E1B2C),
    surface = Color(0xFF14243A),
    surfaceVariant = Color(0xFF1C3050),
    onBackground = Color(0xFFE3ECF5),
    onSurface = Color(0xFFE3ECF5),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF00796B),
    tertiary = Color(0xFFEF6C00),
)

/** Colors used for transaction direction across all screens. */
object LedgerColors {
    val credit = Color(0xFF66BB6A)
    val debit = Color(0xFFEF5350)
}

@Composable
fun MMbankTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
