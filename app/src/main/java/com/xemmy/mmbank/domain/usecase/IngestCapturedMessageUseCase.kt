package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.parser.BuiltInRules
import com.xemmy.mmbank.domain.parser.RuleCompiler
import com.xemmy.mmbank.domain.parser.TransactionParser
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository

/**
 * The single entry point both capture channels (notification listener + SMS
 * receiver) push into:
 *
 *   captured text -> [TransactionParser] (custom rules first, then built-ins)
 *                 -> sliding-window dedup + insert in the repository.
 *
 * Custom rules are re-read on every message so edits in the Rule Engine UI
 * take effect immediately, without restarting the services.
 */
class IngestCapturedMessageUseCase(
    private val ruleRepository: ParsingRuleRepository,
    private val transactionRepository: TransactionRepository,
    private val fallbackCurrencyProvider: () -> String,
) {

    suspend operator fun invoke(message: CapturedMessage): IngestResult {
        val customRules = ruleRepository.getEnabled().mapNotNull(RuleCompiler::compile)
        val parser = TransactionParser(
            rules = customRules + BuiltInRules.all,
            fallbackCurrency = fallbackCurrencyProvider(),
        )
        val parsed = parser.parse(message) ?: return IngestResult.NoMatch
        return transactionRepository.recordParsed(parsed, message)
    }
}
