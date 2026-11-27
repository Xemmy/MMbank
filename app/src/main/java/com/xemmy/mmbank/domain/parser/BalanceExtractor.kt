package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import java.math.BigDecimal

/**
 * Reads the balance a bank prints at the end of most messages -- "Your
 * available account balance is AED 46120.85", "Avl. card bal is 17296.72",
 * "Your available Tabby Card limit is AED 6676.12", "الرصيد المتوفر 106494.95".
 *
 * Until now that figure was thrown away (the amount rules were merely
 * positioned so they wouldn't mistake it for the amount). Kept, it is the
 * bank's own statement of what the account holds, which is what lets the app
 * notice when its books disagree (see
 * [com.xemmy.mmbank.domain.ledger.IntegrityChecker]).
 *
 * Independent of the amount rules on purpose: it runs on the normalized text
 * of a message that has already parsed, and can only add information.
 *
 * Wording decides the [ReportedBalanceKind]: anything about a "limit" or a
 * "card" balance is credit still available; everything else is a balance. The
 * checker, which knows the account's type, decides what that means for it.
 */
object BalanceExtractor {

    data class ReportedBalance(val value: BigDecimal, val kind: ReportedBalanceKind)

    private const val CUR = GenericExtractors.CUR
    private const val NUM = GenericExtractors.NUM

    // "Available [Tabby Card|account|card] balance|limit [is|:] [now] [AED] 1,234.56"
    private val ENGLISH_AVAILABLE = Regex(
        "(?<![A-Za-z])(?<kw>(?:available|avail\\.?|avl\\.?|current)\\s+(?:[A-Za-z]+\\s+){0,2}?(?:bal(?:ance)?|limit))" +
            "\\s*(?:is|are|:|=)?\\s*(?:now\\s+)?(?<neg>-\\s?)?(?:(?<cur>$CUR)(?![A-Za-z])\\s?)?(?<neg2>-\\s?)?(?<num>$NUM)(?!\\.?\\d)",
        RegexOption.IGNORE_CASE,
    )

    // "Balance is AED 100" / "Bal: AED 100" -- currency required, so a bare
    // number after the word "balance" in prose is never taken.
    private val ENGLISH_PLAIN = Regex(
        "(?<![A-Za-z])(?<kw>bal(?:ance)?)\\s*(?:is|:|=)\\s*(?:now\\s+)?(?<neg>-\\s?)?(?<cur>$CUR)(?![A-Za-z])\\s?(?<neg2>-\\s?)?(?<num>$NUM)(?!\\.?\\d)",
        RegexOption.IGNORE_CASE,
    )

    // "الرصيد المتوفر 106494.95" / "رصيد حسابك الحالي AED44081.95" / "والرصيد المتاح هو AED 35994.95"
    private val ARABIC = Regex(
        "(?<kw>الرصيد\\s+المتوفر|الرصيد\\s+المتاح|رصيد\\s+حسابك\\s+الحالي|الرصيد\\s+الحالي)" +
            "\\s*(?:هو|:)?\\s*(?<neg>-\\s?)?(?:(?<cur>$CUR)(?![A-Za-z])\\s?)?(?<neg2>-\\s?)?(?<num>$NUM)(?!\\.?\\d)",
        RegexOption.IGNORE_CASE,
    )

    private val CREDIT_WORDS = Regex("\\blimit\\b|\\bcard\\b", RegexOption.IGNORE_CASE)

    /**
     * The first balance statement in [text] ([TextNormalizer]-normalized), or
     * null. [transactionCurrency] guards against a balance in another
     * currency than the transaction: the stored figure carries no currency of
     * its own, so a mismatch is dropped rather than recorded wrongly.
     */
    fun extract(text: String, transactionCurrency: String): ReportedBalance? {
        val best = listOf(ENGLISH_AVAILABLE, ENGLISH_PLAIN, ARABIC)
            .mapNotNull { it.find(text) }
            .minByOrNull { it.range.first }
            ?: return null

        val currency = best.groupOrNull("cur")?.let(CurrencyNormalizer::normalize)
        if (currency != null && !currency.equals(transactionCurrency, ignoreCase = true)) return null

        val magnitude = best.groupOrNull("num")?.replace(",", "")?.toBigDecimalOrNull() ?: return null
        val negative = best.groupOrNull("neg") != null || best.groupOrNull("neg2") != null
        val value = if (negative && magnitude.signum() != 0) magnitude.negate() else magnitude
        val kind = if (CREDIT_WORDS.containsMatchIn(best.groupOrNull("kw").orEmpty())) {
            ReportedBalanceKind.AVAILABLE_CREDIT
        } else {
            ReportedBalanceKind.ACCOUNT_BALANCE
        }
        return ReportedBalance(value, kind)
    }
}
