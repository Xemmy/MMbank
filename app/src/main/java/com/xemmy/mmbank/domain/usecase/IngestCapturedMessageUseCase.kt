package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
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
 */
class IngestCapturedMessageUseCase(
    private val customRules: CompiledRuleCache,
    private val transactionRepository: TransactionRepository,
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
        val parsed = parser.parse(message) ?: return IngestResult.NoMatch
        return transactionRepository.recordParsed(parsed, message)
    }
}
