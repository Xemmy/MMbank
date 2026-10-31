package com.xemmy.mmbank.domain.account

/**
 * Matches the account/card number a bank message reveals ("*617",
 * "XXX810001", "card ending 4321") against the numbers the user put on their
 * accounts -- the main account number plus any extra card numbers
 * ([com.xemmy.mmbank.domain.model.Account.cardEndings]).
 *
 * Comparison is on digits only, by shared suffix, so masking and differing
 * lengths don't matter. Two rules keep it from filing money in the wrong
 * place, which silently moves it between balances:
 *  - fewer than [MIN_DIGITS] digits on either side never matches -- "*7" is
 *    not evidence of anything;
 *  - when several accounts still match after preferring the transaction's
 *    currency, the longest shared suffix wins, and a tie returns null so the
 *    learned merchant rule (or the user) decides instead of a coin flip.
 */
object AccountNumberMatcher {

    const val MIN_DIGITS = 3

    data class Candidate(
        val accountId: Long,
        val accountNumber: String?,
        val cardEndings: String?,
        val currencyCode: String,
    )

    /** Every digit string an account answers to, each at least [MIN_DIGITS] long. */
    fun numbersOf(candidate: Candidate): List<String> =
        (listOfNotNull(candidate.accountNumber) + candidate.cardEndings.orEmpty().split(',', ';', '/', '\n'))
            .map { it.filter(Char::isDigit) }
            .filter { it.length >= MIN_DIGITS }
            .distinct()

    fun match(detected: String, currencyCode: String, candidates: List<Candidate>): Long? {
        val digits = detected.filter(Char::isDigit)
        if (digits.length < MIN_DIGITS) return null

        // Best overlap per account: how many trailing digits the two share.
        val scored = candidates.mapNotNull { candidate ->
            val best = numbersOf(candidate)
                .filter { digits.endsWith(it) || it.endsWith(digits) }
                .maxOfOrNull { minOf(it.length, digits.length) }
            best?.let { candidate to it }
        }
        if (scored.isEmpty()) return null
        if (scored.size == 1) return scored.single().first.accountId

        val sameCurrency = scored.filter { it.first.currencyCode.equals(currencyCode, ignoreCase = true) }
        val pool = sameCurrency.ifEmpty { scored }
        val top = pool.maxOf { it.second }
        val winners = pool.filter { it.second == top }
        return winners.singleOrNull()?.first?.accountId
    }
}
