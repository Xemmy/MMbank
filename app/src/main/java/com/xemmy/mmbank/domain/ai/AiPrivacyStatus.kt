package com.xemmy.mmbank.domain.ai

/** Pure wording and rules for how visibly the app says where AI parsing sends text. */
object AiPrivacyStatus {
    /** True only when AI parsing is on AND pointed at an online provider. */
    fun sendsTextOffDevice(enabled: Boolean, mode: AiProviderMode): Boolean =
        enabled && mode == AiProviderMode.ONLINE

    /** One-line summary for the Settings menu row. */
    fun menuSubtitle(enabled: Boolean, mode: AiProviderMode): String = when {
        !enabled -> "Off · read messages the rules miss"
        mode == AiProviderMode.ONLINE -> "On · online provider — message text leaves your device"
        else -> "On · on-device server"
    }

    const val ONLINE_BANNER_TITLE = "Online AI is on"
    const val ONLINE_BANNER_BODY =
        "Bank message text the rules can't read is sent to your chosen online provider. " +
            "Switch to On-device to keep it on this phone."
}
