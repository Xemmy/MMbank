package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.util.LogRedactor

/**
 * Masks what a bank message carries that no transaction parser needs, before
 * the text is POSTed to an online AI provider (backlog B.6 / #33).
 *
 * Masked: card, account and IBAN numbers (last 4 digits kept, as the parser
 * only ever uses the ending -- see [LogRedactor]), one-time passwords and
 * other security codes, e-mail addresses and international phone numbers.
 *
 * Deliberately left alone: amounts, currencies, merchants, dates and
 * balances. The model needs those to answer, and a redactor that damaged
 * them would turn into silent mis-parses. Idempotent, and a message with
 * nothing sensitive in it comes back unchanged.
 *
 * Applies only to online providers; the on-device engine's text never leaves
 * the phone (see [com.xemmy.mmbank.data.ai.OpenAiCompatibleEngine]).
 */
object AiPayloadRedactor {

    const val CODE_MASK = "••••"

    private const val CODE_WORDS =
        """otp|one[- ]?time[- ](?:password|passcode|code|pin)|verification code|security code|passcode|password|cvv2?|cvc|pin"""

    /**
     * "OTP is 482913", "verification code: 1234", "PIN 4321" -- the keyword stays, the digits go.
     * Only separators may sit between keyword and code, so an amount further along the
     * sentence ("verification code for AED 250") is never mistaken for it.
     */
    private val codeAfterKeyword = Regex(
        """(\b(?:$CODE_WORDS)\b[ :=\-]*(?:(?:is|was)\s+)?)\d{3,8}\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "OTP for transaction of AED 250 at NOON is 123456" -- the code is whatever follows the first "is". */
    private val codeAfterIs = Regex(
        """(\b(?:$CODE_WORDS)\b[^.\n]{0,60}?\bis\s+)\d{4,8}\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "482913 is your OTP" -- the digits come first. */
    private val codeBeforeKeyword = Regex(
        """\b\d{4,8}\b(?=\s+(?:is|as)\s+(?:your|the)\s+(?:otp|one[- ]?time|verification|security|login|passcode))""",
        RegexOption.IGNORE_CASE,
    )

    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    /** Only explicit international numbers; a bare digit run could just as well be an amount. */
    private val phone = Regex("""\+\d{1,3}[ -]?\d[\d -]{6,12}\d""")

    fun redact(text: String): String {
        var out = LogRedactor.redactSensitiveData(text)
        out = codeAfterKeyword.replace(out) { it.groupValues[1] + CODE_MASK }
        out = codeAfterIs.replace(out) { it.groupValues[1] + CODE_MASK }
        out = codeBeforeKeyword.replace(out, CODE_MASK)
        out = email.replace(out, "[email]")
        out = phone.replace(out, "[phone]")
        return out
    }
}
