package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xemmy.mmbank.domain.model.BackgroundStyle

/** One soft radial glow — pure data, no drawing logic, so [specFor] stays JVM-testable. */
internal data class GlowSpec(
    val color: Color,
    /** Center as a fraction of the draw area's size; may fall outside 0f..1f to anchor off-canvas. */
    val centerFraction: Offset,
    /** Peak alpha at the glow's core. Kept in the 0.08f-0.15f band so it reads as a hint, not a wash. */
    val alpha: Float,
    /** Radius as a fraction of the canvas's longest side. */
    val radiusFraction: Float = 0.9f,
)

/** Base fill + glow layers for one [BackgroundStyle] — pure data, no drawing logic. */
internal data class AmbientSpec(
    val surface: Color,
    val glows: List<GlowSpec>,
)

internal fun specFor(style: BackgroundStyle): AmbientSpec = when (style) {
    BackgroundStyle.OLED_OBSIDIAN -> AmbientSpec(
        surface = Color(0xFF000000),
        glows = listOf(
            GlowSpec(color = Color(0xFF6C3CE9), centerFraction = Offset(0.8f, -0.2f), alpha = 0.14f),
        ),
    )
    BackgroundStyle.AURORA_MUTED -> AmbientSpec(
        surface = Color(0xFF0E0F1A),
        glows = listOf(
            GlowSpec(color = Color(0xFF4C5FD6), centerFraction = Offset(0.1f, 0.05f), alpha = 0.12f),
            GlowSpec(color = Color(0xFF1FA391), centerFraction = Offset(0.95f, 0.9f), alpha = 0.10f),
        ),
    )
    BackgroundStyle.WARM_CASHMERE -> AmbientSpec(
        surface = Color(0xFFF3EAE0),
        glows = listOf(
            GlowSpec(color = Color(0xFFE0A45C), centerFraction = Offset(0.85f, -0.1f), alpha = 0.12f),
            GlowSpec(color = Color(0xFF4A3222), centerFraction = Offset(0.05f, 1.05f), alpha = 0.08f),
        ),
    )
    BackgroundStyle.MINIMAL_SLATE -> AmbientSpec(
        surface = Color(0xFFEDEEF2),
        glows = emptyList(),
    )
}

/** Label shown in the Appearance settings' style picker. */
val BackgroundStyle.displayName: String
    get() = when (this) {
        BackgroundStyle.OLED_OBSIDIAN -> "OLED Obsidian"
        BackgroundStyle.AURORA_MUTED -> "Aurora Muted"
        BackgroundStyle.WARM_CASHMERE -> "Warm Cashmere"
        BackgroundStyle.MINIMAL_SLATE -> "Minimal Slate"
    }

/** One-line description shown under each preset's name in the Appearance settings picker. */
val BackgroundStyle.description: String
    get() = when (this) {
        BackgroundStyle.OLED_OBSIDIAN -> "Deep violet glow, pure black surface"
        BackgroundStyle.AURORA_MUTED -> "Indigo and teal, soft wide falloff"
        BackgroundStyle.WARM_CASHMERE -> "Faint amber and espresso warmth"
        BackgroundStyle.MINIMAL_SLATE -> "Flat surface, zero color bleed"
    }

/**
 * Draws [style]'s ultra-subtle ambient wash behind whatever this modifier is
 * attached to: an opaque base fill, one or two low-alpha radial glows, then a
 * translucent scrim of the current [MaterialTheme] background on top so text
 * and card contrast stay crisp regardless of preset. [drawWithCache] rebuilds
 * the brushes only when the draw area's size changes, never on recomposition
 * (e.g. a balance tick or a list scroll), so this stays cheap everywhere it's
 * applied. A true Gaussian blur (RenderEffect) isn't used for the glows: it
 * would blur every layer sharing this modifier's chain, including the app's
 * content, not just the wash — the soft radial falloff below approximates the
 * "100dp blur" look without that cost or side effect.
 */
@Composable
fun Modifier.ambientBackground(style: BackgroundStyle): Modifier {
    val scrimColor = MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
    return drawWithCache {
        val spec = specFor(style)
        val maxDimension = size.maxDimension
        val brushes = spec.glows.map { glow ->
            val center = Offset(size.width * glow.centerFraction.x, size.height * glow.centerFraction.y)
            val radius = (maxDimension * glow.radiusFraction).coerceAtLeast(1f)
            Triple(
                Brush.radialGradient(
                    colors = listOf(
                        glow.color.copy(alpha = glow.alpha),
                        glow.color.copy(alpha = glow.alpha * 0.35f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                center,
                radius,
            )
        }
        onDrawBehind {
            drawRect(color = spec.surface)
            brushes.forEach { (brush, center, radius) ->
                drawCircle(brush = brush, radius = radius, center = center)
            }
            drawRect(color = scrimColor)
        }
    }
}
