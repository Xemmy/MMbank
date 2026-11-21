package com.xemmy.mmbank.ui.util

/** What the window must do to keep balances out of screenshots and Recents. */
data class SecureScreenDecision(
    /** Set FLAG_SECURE (blocks screenshots/recording, blanks the Recents thumbnail). */
    val flagSecure: Boolean,
    /** Ask the system to omit the Recents screenshot (API 33+ only). */
    val hideRecentsScreenshot: Boolean,
)

object SecureScreenPolicy {
    private const val API_RECENTS_SCREENSHOT = 33

    /**
     * "Block screenshots" always sets FLAG_SECURE. With app lock on, the
     * Recents thumbnail must also be blank: API 33+ has a dedicated switch
     * that leaves normal screenshots alone; below that FLAG_SECURE is the
     * only way.
     */
    fun decide(blockScreenshots: Boolean, appLockEnabled: Boolean, sdkInt: Int): SecureScreenDecision {
        val recentsViaApi = appLockEnabled && sdkInt >= API_RECENTS_SCREENSHOT
        val recentsViaFlag = appLockEnabled && sdkInt < API_RECENTS_SCREENSHOT
        return SecureScreenDecision(
            flagSecure = blockScreenshots || recentsViaFlag,
            hideRecentsScreenshot = recentsViaApi,
        )
    }
}
