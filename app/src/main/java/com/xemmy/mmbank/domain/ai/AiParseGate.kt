package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.GenericExtractors
import com.xemmy.mmbank.domain.parser.SocialApps
import com.xemmy.mmbank.domain.parser.TextNormalizer

/**
 * Decides whether an unmatched message is worth a Gate 2 model pass. The
 * notification listener sees every notification on the device, so this must
 * be strict: only messages that plausibly carry money (a currency-adjacent
 * amount, same bar as signature routing) from non-social senders reach the
 * worker queue — and only while the on-device model is actually runnable.
 */
object AiParseGate {

    fun shouldAttempt(message: CapturedMessage, aiAvailable: Boolean): Boolean {
        if (!aiAvailable) return false
        // Same precedent as the built-in rules: chat/social notifications are
        // never auto-parsed (a Telegram channel quoting prices is not a spend).
        if (message.source == TransactionSource.PUSH_NOTIFICATION &&
            message.sender?.let(SocialApps::isSocial) == true
        ) {
            return false
        }
        val text = TextNormalizer.normalize(
            listOfNotNull(message.title, message.body).filter { it.isNotBlank() }.joinToString(" ")
        )
        if (text.isBlank()) return false
        return GenericExtractors.extractAmount(text) != null
    }
}
