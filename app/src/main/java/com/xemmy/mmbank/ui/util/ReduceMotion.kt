package com.xemmy.mmbank.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Pure rule: the system "remove animations" setting reports an animator duration scale of 0. */
fun isReduceMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** True when the user turned system animations off; skips springs and the AGSL shader. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        isReduceMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}
