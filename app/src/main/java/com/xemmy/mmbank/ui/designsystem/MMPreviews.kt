package com.xemmy.mmbank.ui.designsystem

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Multipreview for every design-system component: dark, light, 200% font scale and RTL (Improvement Plan §8.4). */
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true)
@Preview(name = "Font 200%", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "RTL", locale = "ar", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
annotation class MMPreviews
