package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.parser.GenericExtractors
import com.xemmy.mmbank.domain.parser.TextNormalizer

/**
 * Decides whether an unparsed message is worth forwarding to Gate 2 at all.
 * "Gate 1 had rules to try and none matched" is not, by itself, a strong
 * enough signal -- a digit-bearing but entirely non-financial notification
 * (e.g. "Download complete - 26.96 MB") clears that bar too. This adds the
 * same plausibility floor [GenericExtractors] already uses for its
 * signature-routing fallback: somewhere in the title or body there must be a
 * currency-adjacent amount. A bare number with nothing recognizable next to
 * it is not enough, so messages like the download example never reach the AI
 * engine -- local OR online, but this matters most for online, where a miss
 * means sending arbitrary notification text to a third party.
 */
object AiForwardGate {
    fun isPlausibleTransaction(title: String?, body: String): Boolean {
        if (GenericExtractors.extractAmount(TextNormalizer.normalize(body)) != null) return true
        return title?.let { GenericExtractors.extractAmount(TextNormalizer.normalize(it)) != null } == true
    }
}
