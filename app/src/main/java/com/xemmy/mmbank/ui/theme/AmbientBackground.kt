package com.xemmy.mmbank.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xemmy.mmbank.domain.model.BackgroundPreset
import kotlin.math.cos
import kotlin.math.sin

/** Base fill + drifting blob colors for one [BackgroundPreset] — pure data, no drawing logic. */
private data class AmbientSpec(
    val base: Color,
    val blobs: List<Color>,
    /** One full drift cycle, in ms. Slower = calmer, cheaper to redraw. */
    val cycleMillis: Int,
)

private fun specFor(preset: BackgroundPreset): AmbientSpec = when (preset) {
    BackgroundPreset.AURORA -> AmbientSpec(
        base = Color(0xFF120F1E),
        blobs = listOf(Color(0xFF6C5CE7), Color(0xFF00D2D3), Color(0xFFE84393)),
        cycleMillis = 18_000,
    )
    BackgroundPreset.MIDNIGHT_OLED -> AmbientSpec(
        base = Color(0xFF000000),
        blobs = listOf(Color(0xFF101010)),
        cycleMillis = 30_000,
    )
    BackgroundPreset.CYBER_NEON -> AmbientSpec(
        base = Color(0xFF0A0014),
        blobs = listOf(Color(0xFFFF00E5), Color(0xFF00F0FF)),
        cycleMillis = 9_000,
    )
    BackgroundPreset.MINIMAL_FROST -> AmbientSpec(
        base = Color(0xFFF4F6F9),
        blobs = listOf(Color(0xFFDCE6F5), Color(0xFFE8ECF3)),
        cycleMillis = 24_000,
    )
}

/** Label shown in the Dashboard header's background-style picker. */
val BackgroundPreset.displayName: String
    get() = when (this) {
        BackgroundPreset.AURORA -> "Aurora"
        BackgroundPreset.MIDNIGHT_OLED -> "Midnight OLED"
        BackgroundPreset.CYBER_NEON -> "Cyber Neon"
        BackgroundPreset.MINIMAL_FROST -> "Minimal Frost"
    }

/**
 * Draws [preset]'s subtle animated radial-gradient wash behind whatever this
 * modifier is attached to (typically a full-screen Box sitting behind a
 * LazyColumn whose own item surfaces cover most of it). Every frame's motion
 * is confined to [drawBehind]'s draw phase — reading the animated [Float]
 * there invalidates only that layer's drawing, never triggers a recomposition
 * of this modifier's content, so it stays cheap during list scrolling (see
 * Glow.kt's [glowBackdrop] for the same static-case idiom this builds on).
 */
fun Modifier.ambientBackground(preset: BackgroundPreset): Modifier = composed {
    val spec = specFor(preset)
    val transition = rememberInfiniteTransition(label = "ambientBackground")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = spec.cycleMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientPhase",
    )
    this
        .background(spec.base)
        .drawBehind {
            if (size.minDimension <= 0f) return@drawBehind
            val maxRadius = size.maxDimension * 0.65f
            spec.blobs.forEachIndexed { index, color ->
                // Each blob orbits its own anchor corner at a slightly different
                // speed/phase offset so they drift independently, not in lockstep.
                val angle = (phase * 2f * Math.PI.toFloat()) + (index * 2.1f)
                val anchorX = size.width * (0.2f + 0.6f * (index % 2))
                val anchorY = size.height * (0.15f + 0.7f * ((index + 1) % 2))
                val driftX = cos(angle) * size.width * 0.12f
                val driftY = sin(angle) * size.height * 0.12f
                val center = Offset(anchorX + driftX, anchorY + driftY)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.28f), Color.Transparent),
                        center = center,
                        radius = maxRadius,
                    ),
                    radius = maxRadius,
                    center = center,
                )
            }
        }
}
