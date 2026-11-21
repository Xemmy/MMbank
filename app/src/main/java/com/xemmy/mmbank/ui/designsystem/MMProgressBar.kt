package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xemmy.mmbank.ui.theme.MMTheme

/** Clamps value/max to 0..1; a non-positive or NaN max gives 0. */
fun progressFraction(value: Double, max: Double): Float {
    if (max.isNaN() || max <= 0.0 || value.isNaN()) return 0f
    return (value / max).coerceIn(0.0, 1.0).toFloat()
}

/** Warning from [warnAt], danger at or above [dangerAt]; null (accent) below. */
fun progressTone(fraction: Float, warnAt: Float = 0.8f, dangerAt: Float = 1f): MMTone? = when {
    fraction >= dangerAt -> MMTone.DANGER
    fraction >= warnAt -> MMTone.WARNING
    else -> null
}

/** Linear progress/budget bar; the tone shifts to warning/danger as it fills. */
@Composable
fun MMProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: MMTone? = progressTone(fraction),
) {
    val colors = MMTheme.colors
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = if (tone != null) colors.toneColor(tone) else colors.accent,
        trackColor = colors.borderSubtle,
    )
}
