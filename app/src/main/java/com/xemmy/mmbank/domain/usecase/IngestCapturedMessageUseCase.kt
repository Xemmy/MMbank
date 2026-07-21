package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.ai.AiForwardGate
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BuiltInRules
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.parser.SocialApps
import com.xemmy.mmbank.domain.parser.TransactionParser
import com.xemmy.mmbank.domain.repository.TransactionRepository

/**
 * The single entry point both capture channels (notification listener + SMS
 * receiver) push into:
 *
 *   captured text -> [TransactionParser] (custom rules first, then built-ins)
 *                 -> sliding-window dedup + insert in the repository.
 *
 * Battery shape: this runs for every notification on the device, so the fast
 * paths matter. Digit-free messages bail out before touching anything, and
 * custom rules come precompiled from [CompiledRuleCache] instead of a Room
 * read + regex compile per message. Rule edits still apply immediately —
 * the cache tracks the parsing_rules table.
 *
 * Gate 1 / Gate 2: [onGate1Miss] fires only when a message plausibly looked
 * like a transaction (had rules to try, none matched, AND
 * [AiForwardGate] finds a currency-adjacent amount somewhere in it) -- not on
 * the digit-free or social-app-filtered short-circuits (true negatives, not
 * parse failures), and not on a digit-bearing but non-financial notification
 * (e.g. "Download complete - 26.96 MB") that would otherwise reach Gate 2's
 * AI engine -- local or, worse, an online provider -- for nothing. It never
 * blocks or alters this function's own NoMatch result; the caller
 * (AppContainer) uses it to enqueue the Gate 2 worker as a fire-and-forget
 * side effect.
 */
class IngestCapturedMessageUseCase(
    private val customRules: CompiledRuleCache,
    private val transactionRepository: TransactionRepository,
    private val onGate1Miss: (CapturedMessage) -> Unit = {},
    private val fallbackCurrencyProvider: () -> String,
) {

    suspend operator fun invoke(message: CapturedMessage): IngestResult {
        // Every rule requires an amount and an amount requires a digit
        // (Character.isDigit covers Arabic-Indic digits too, pre-normalization),
        // so most chat/media notifications exit here for the cost of a scan.
        val hasDigit = message.body.any { it.isDigit() } ||
            message.title?.any { it.isDigit() } == true
        if (!hasDigit) return IngestResult.NoMatch

        val custom = customRules.enabledRules()
        val sender = message.sender

        val rules = if (
            message.source == TransactionSource.PUSH_NOTIFICATION &&
            sender != null &&
            SocialApps.isSocial(sender)
        ) {
            // Social/chat notifications (a Telegram channel quoting numbers,
            // say) must never match the generic built-ins. Only custom rules
            // that explicitly target the package may parse them.
            custom.filter { it.senderPattern?.containsMatchIn(sender) == true }
        } else {
            custom + BuiltInRules.all
        }
        if (rules.isEmpty()) return IngestResult.NoMatch

        val parser = TransactionParser(rules, fallbackCurrencyProvider())
        val parsed = parser.parse(message) ?: run {
            if (AiForwardGate.isPlausibleTransaction(message.title, message.body)) {
                onGate1Miss(message)
            }
            return IngestResult.NoMatch
        }

        // "Transaction adjusted" messages (Tabby partial refunds / extra
        // charges) correct an already-recorded purchase rather than describe a
        // new spend, so they must not skew analytics. They stay in the ledger
        // (balances remain right) but carry the exclusion flag.
        //
        // A recognized credit-card statement payment (the credit leg landing
        // on the card) is similarly not a fresh spend or fresh income — it's
        // one side of an internal transfer. Tagging it here (excludeFromStats
        // + SpendingCategory.TRANSFERS) is what lets TransactionRepositoryImpl's
        // TransferMatcher step later find and re-tag its debit counterpart too.
        val flagged = when {
            parsed.matchedRuleName.startsWith(ADJUSTMENT_RULE_PREFIX) -> parsed.copy(excludeFromStats = true)
            parsed.matchedRuleName.startsWith(TRANSFER_RULE_PREFIX) ->
                parsed.copy(excludeFromStats = true, spendingCategoryOverride = SpendingCategory.TRANSFERS)
            else -> parsed
        }
        return transactionRepository.recordParsed(flagged, message)
    }

    companion object {
        /** The priority-20 builtin.en.adjust.* rules in [BuiltInRules]. */
        const val ADJUSTMENT_RULE_PREFIX = "builtin.en.adjust."

        /** The priority-15 builtin.en.transfer.* rules in [BuiltInRules] (credit-card statement payments). */
        const val TRANSFER_RULE_PREFIX = "builtin.en.transfer."
    }
}
