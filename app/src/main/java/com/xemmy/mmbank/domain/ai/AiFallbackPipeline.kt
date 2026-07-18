package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.GenericExtractors
import com.xemmy.mmbank.domain.parser.TextNormalizer

/**
 * Gate 2 orchestration, fully autonomous: model extraction -> record through
 * the same dedup+insert path as Gate 1 -> self-heal the rule table. Pure
 * Kotlin over injected suspend seams so the whole flow is JVM-testable; the
 * expedited worker is just a thin Android adapter around [run].
 */
class AiFallbackPipeline(
    private val extract: suspend (CapturedMessage) -> AiExtraction?,
    private val record: suspend (ParsedTransaction, CapturedMessage) -> IngestResult,
    private val enabledRules: suspend () -> List<ParsingRule>,
    private val saveRule: suspend (ParsingRule) -> Long,
    private val fallbackCurrency: () -> String,
) {

    sealed interface Outcome {
        /** Engine missing/failed or output unusable — nothing happened. */
        data object Unavailable : Outcome

        /** The model decided this message is not a money movement. */
        data object NotATransaction : Outcome

        data class Recorded(val result: IngestResult, val learnedRule: ParsingRule?) : Outcome
    }

    suspend fun run(message: CapturedMessage): Outcome {
        val tx = when (val extraction = extract(message)) {
            null -> return Outcome.Unavailable
            is AiExtraction.NotATransaction -> return Outcome.NotATransaction
            is AiExtraction.Transaction -> extraction
        }

        val text = TextNormalizer.normalize(
            listOfNotNull(message.title, message.body).filter { it.isNotBlank() }.joinToString(" ")
        )
        // The strict keyset carries no direction: explicit wording decides,
        // and a spend is the right default for bank alerts otherwise.
        val direction = GenericExtractors.detectDirectionOverride(text) ?: TransactionDirection.DEBIT

        val parsed = ParsedTransaction(
            amount = tx.amount,
            currencyCode = tx.currency ?: fallbackCurrency(),
            direction = direction,
            merchant = tx.merchant,
            matchedRuleName = AI_RULE_NAME,
            accountNumberSuffix = GenericExtractors.extractAccountSuffix(text),
        )
        val result = record(parsed, message)

        // Self-heal only on a real insert: a Duplicate means another channel
        // already delivered this transaction (and likely this same lesson).
        val learned = if (result is IngestResult.Inserted) learn(message, tx, direction) else null
        return Outcome.Recorded(result, learned)
    }

    private suspend fun learn(
        message: CapturedMessage,
        tx: AiExtraction.Transaction,
        direction: TransactionDirection,
    ): ParsingRule? {
        val candidate = RegexSynthesizer.synthesize(message, tx, direction) ?: return null
        if (enabledRules().any { it.pattern == candidate.pattern }) return null
        val id = saveRule(candidate)
        return candidate.copy(id = id)
    }

    companion object {
        /** matchedRuleName stamped on transactions Gate 2 recorded. */
        const val AI_RULE_NAME = "ai.gemma.fallback"
    }
}
