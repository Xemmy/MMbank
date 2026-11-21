package com.xemmy.mmbank.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.GlowPalette
import com.xemmy.mmbank.ui.util.rememberReduceMotion

/**
 * Pure API gate, split out so it's JVM-testable without a device: the fluid
 * AGSL shader needs [RuntimeShader] (API 33+) and is skipped when the user
 * turned animations off -- every other combination falls back to the plain Material spinner.
 */
object AiParsingIndicatorSpec {
    const val SHADER_MIN_API = 33

    fun usesShader(apiLevel: Int, reduceMotion: Boolean): Boolean =
        apiLevel >= SHADER_MIN_API && !reduceMotion
}

private const val FLUID_SHADER = """
    uniform float2 resolution;
    uniform float time;
    layout(color) uniform half4 colorA;
    layout(color) uniform half4 colorB;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / resolution.xy - 0.5;
        float angle = atan(uv.y, uv.x);
        float radius = length(uv);
        float wave = sin(angle * 3.0 + time) * 0.10;
        float mixValue = smoothstep(0.0, 0.55, radius + wave);
        half4 color = mix(colorA, colorB, mixValue);
        float alpha = smoothstep(0.5, 0.40, radius);
        return color * alpha;
    }
"""

/**
 * Loading indicator shown while Gate-2 AI parsing runs (the "Parse with AI"
 * button in the transaction editor). On API 33+ (animations on) it's a small
 * fluid AGSL gradient blob; otherwise (pre-Tiramisu, or reduce-motion)
 * it's the original Material spinner, unchanged.
 */
@Composable
fun AiParsingIndicator(
    modifier: Modifier = Modifier,
) {
    if (AiParsingIndicatorSpec.usesShader(Build.VERSION.SDK_INT, rememberReduceMotion())) {
        FluidShaderIndicator(modifier)
    } else {
        CircularProgressIndicator(modifier = modifier, strokeWidth = 2.dp)
    }
}

@Composable
private fun FluidShaderIndicator(modifier: Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "aiParsingShader")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing)),
        label = "aiParsingShaderTime",
    )
    val colorA = GlowPalette.tabbyTeal
    val colorB = GlowPalette.binanceGold
    Spacer(
        modifier
            .clip(CircleShape)
            .drawWithCache {
                val shader = RuntimeShader(FLUID_SHADER)
                shader.setFloatUniform("resolution", size.width, size.height)
                val brush = ShaderBrush(shader)
                onDrawBehind {
                    shader.setFloatUniform("time", time)
                    shader.setColorUniform(
                        "colorA",
                        android.graphics.Color.valueOf(colorA.red, colorA.green, colorA.blue, colorA.alpha),
                    )
                    shader.setColorUniform(
                        "colorB",
                        android.graphics.Color.valueOf(colorB.red, colorB.green, colorB.blue, colorB.alpha),
                    )
                    drawRect(brush)
                }
            },
    )
}
