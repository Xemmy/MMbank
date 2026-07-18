package com.xemmy.mmbank.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Pure-Kotlin spec for the glassmorphism treatment, split out so the API gate
 * is JVM-testable. Blur relies on RenderEffect, which only exists on API 31+;
 * on older devices it silently does nothing, so the glass look degrades to a
 * slightly more opaque solid translucent surface with the same hairline border.
 */
object GlassSpec {
    /** RenderEffect-backed blur (window backdrop blur included) needs API 31+. */
    const val BLUR_MIN_API = 31

    /** Backdrop blur radius applied behind glass dialogs, in dp (spec 5.2). */
    const val BACKDROP_BLUR_RADIUS_DP = 22

    fun supportsBlur(apiLevel: Int): Boolean = apiLevel >= BLUR_MIN_API

    /**
     * Container translucency: see-through over a blurred backdrop on API 31+,
     * more opaque below 31 where nothing frosts what's behind.
     */
    fun containerAlpha(apiLevel: Int): Float =
        if (supportsBlur(apiLevel)) 0.72f else 0.96f

    /** Radius to request from the window, or 0 when blur can't work. */
    fun backdropBlurRadiusDp(apiLevel: Int): Int =
        if (supportsBlur(apiLevel)) BACKDROP_BLUR_RADIUS_DP else 0
}

/**
 * Shared glass treatment for major overlays: [ObsidianTokens.dialogPanelBlur]/
 * [ObsidianTokens.dialogPanelFallback] + hairline border, clipped to [shape].
 * Pair with [GlassDialogBackdrop] inside dialogs so API 31+ devices actually
 * frost the content behind the overlay.
 */
fun Modifier.glassSurface(
    shape: Shape,
    apiLevel: Int = Build.VERSION.SDK_INT,
): Modifier = composed {
    val container = if (GlassSpec.supportsBlur(apiLevel)) {
        ObsidianTokens.dialogPanelBlur
    } else {
        ObsidianTokens.dialogPanelFallback
    }
    this
        .clip(shape)
        .background(container, shape)
        .border(1.dp, ObsidianTokens.hairlineDialog, shape)
}

/**
 * Call inside a dialog's content to blur what's behind its window on API 31+.
 * A no-op below 31 (the glassSurface fallback opacity carries the look there).
 */
@Composable
fun GlassDialogBackdrop() {
    val radiusDp = GlassSpec.backdropBlurRadiusDp(Build.VERSION.SDK_INT)
    if (radiusDp <= 0) return
    val view = LocalView.current
    val density = LocalDensity.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        if (Build.VERSION.SDK_INT >= GlassSpec.BLUR_MIN_API) {
            window.setBackgroundBlurRadius(with(density) { radiusDp.dp.toPx() }.toInt())
        }
    }
}
