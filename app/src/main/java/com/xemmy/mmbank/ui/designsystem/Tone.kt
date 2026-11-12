package com.xemmy.mmbank.ui.designsystem

import androidx.compose.ui.graphics.Color
import com.xemmy.mmbank.ui.theme.MMColors

/** Status tone shared by cards, banners, tags and progress bars. */
enum class MMTone { INFO, WARNING, DANGER }

/** Pure token lookup so it is unit-testable without Compose. */
fun MMColors.toneColor(tone: MMTone): Color = when (tone) {
    MMTone.INFO -> info
    MMTone.WARNING -> warning
    MMTone.DANGER -> danger
}

/** Spoken label for a tone, so colour is never the only signal. */
fun toneLabel(tone: MMTone): String = when (tone) {
    MMTone.INFO -> "Info"
    MMTone.WARNING -> "Warning"
    MMTone.DANGER -> "Alert"
}
