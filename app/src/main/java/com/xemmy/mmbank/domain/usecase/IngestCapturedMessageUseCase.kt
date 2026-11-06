package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.ai.AiForwardGate
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BuiltInRules
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.parser.IgnoredMessageCache
import com.xemmy.mmbank.domain.parser.SocialApps
import com.xemmy.mmbank.domain.parser.TransactionParser
import com.xemmy.mmbank.domain.repository.TransactionRepository

/**
 * The single entry point both capture channels (notification listener + SMS
 * receiver) push into:
 *
 *   captured text -> [IgnoredMessageCache] (templates the user muted)
 *                 -> [TransactionParser] (custom rules first, then built-ins)
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
    private val ignoredMessages: IgnoredMessageCache? = null,
) {

    /**
     * [dedupWindow] is passed straight through to
     * [TransactionRepository.recordParsed]; only the paste importer ever turns
     * it off, and only for messages whose clock time it doesn't know.
     *
     * [forwardOnMiss] = false suppresses the Gate-2 hand-off for this message.
     * A live capture is one message the user never asked for, so spending a
     * little AI budget to read it is worth it; a paste is a deliberate batch
     * that could be hundreds of messages at once, and quietly draining the
     * day's budget on it is not what someone pressing "Import" is asking for.
     * Those simply report as unparsed -- the add-transaction dialog's manual
     * "Parse with AI" is still there for the individual ones worth spending on.
     */
    suspend operator fun invoke(
        message: CapturedMessage,
        dedupWindow: Boolean = true,
        forwardOnMiss: Boolean = true,
    ): IngestResult {
        // Every rule requires an amount and an amount requires a digit
        // (Character.isDigit covers Arabic-Indic digits too, pre-normalization),
        // so most chat/media notifications exit here for the cost of a scan.
        val hasDigit = message.body.any { it.isDigit() } ||
            message.title?.any { it.isDigit() } == true
        if (!hasDigit) return IngestResult.NoMatch

        // Deliberately after the digit check, not before it: a digit-free
        // message is already leaving as NoMatch, so muting it would change
        // nothing while costing every chat notification a fingerprint pass.
        if (ignoredMessages?.shouldIgnore(message.body, message.title) == true) return IngestResult.Ignored

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
            if (forwardOnMiss && AiForwardGate.isPlausibleTransaction(message.title, message.body)) {
                onGate1Miss(message)
            }
            return IngestResult.NoMatch
        }

        // What the message *is* (spend, refund, card payment...) is decided
        // in the repository by TransactionKindDetector, once the account --
        // and so whether it's a credit card -- is known. The one thing settled
        // here is the spending category of a recognized card-payment credit:
        // it's a TRANSFERS row regardless of whatever "merchant" text happens
        // to be nearby, which is also what lets TransferMatcher find and
        // re-tag its debit counterpart.
        val flagged = if (parsed.matchedRuleName.startsWith(TRANSFER_RULE_PREFIX)) {
            parsed.copy(spendingCategoryOverride = SpendingCategory.TRANSFERS)
        } else {
            parsed
        }
        return transactionRepository.recordParsed(flagged, message, dedupWindow)
    }

    companion object {
        /** The priority-15 builtin.en.transfer.* rules in [BuiltInRules] (credit-card statement payments). */
        const val TRANSFER_RULE_PREFIX = "builtin.en.transfer."
    }
}
