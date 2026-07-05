package com.xemmy.mmbank.domain.parser

/**
 * Chat / social / media apps whose notifications routinely contain numbers
 * that are not transactions — e.g. a Telegram channel quoting prices, which
 * the generic built-in rules would happily mis-parse ("paid 500 USD…").
 *
 * Notifications from these packages never run against the built-in rules.
 * They can still be captured deliberately: a custom rule whose sender filter
 * matches the package name keeps working, so an intentional bot/channel feed
 * can be mapped without re-opening the false-positive hole.
 */
object SocialApps {

    private val exactPackages = setOf(
        // WhatsApp
        "com.whatsapp",
        "com.whatsapp.w4b",
        // Meta
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.barcelona", // Threads
        // Other messengers
        "com.viber.voip",
        "jp.naver.line.android",
        "com.tencent.mm", // WeChat
        "com.imo.android.imoim",
        "com.skype.raider",
        "com.discord",
        "com.slack",
        // Social / media
        "com.twitter.android",
        "com.snapchat.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok (Asia)
        "com.ss.android.ugc.aweme", // Douyin
        "com.google.android.youtube",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.linkedin.android",
    )

    private val packagePrefixes = listOf(
        "org.telegram.", // official Telegram, web wrapper, beta, Plus forks
        "org.thunderdog.challegram", // Telegram X
        "org.thoughtcrime.securesms", // Signal + beta
    )

    fun isSocial(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg in exactPackages || packagePrefixes.any(pkg::startsWith)
    }
}
