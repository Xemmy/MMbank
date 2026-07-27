package com.xemmy.mmbank.data.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.ai.AiResponseParser
import com.xemmy.mmbank.domain.ai.AiTransactionPrompt
import com.xemmy.mmbank.domain.ai.LocalAiEngine

/**
 * Gate 2 of the dual-gate parser: an expedited, best-effort worker enqueued
 * whenever Gate 1 (the regex/signature engine in IngestCapturedMessageUseCase)
 * attempted to match a captured message and failed. Forwards the raw text to
 * the local Gemma 4 E2B engine (via [LocalAiManager]) and, on a valid
 * transaction extraction, hands it to the self-healing hook.
 *
 * Fails silently (Result.success with no side effect) on every non-fatal
 * condition -- incapable hardware, an uninitialized engine, a generation
 * error, or a malformed/negative response -- so Gate 2 can never turn into a
 * crash loop or a retry storm; the message simply stays unparsed, exactly as
 * it would have without Gate 2.
 */
class AiParsingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val rawMessage = inputData.getString(KEY_RAW_MESSAGE)
        if (rawMessage.isNullOrBlank()) return Result.success()

        val container = (applicationContext as MMBankApp).container
        val manager = container.localAiManager
        if (!manager.isConfigured()) return Result.success()

        val engine = manager.engine()
        if (!engine.isReady) return Result.success()

        val generated = engine.generate(AiTransactionPrompt.build(rawMessage), LocalAiEngine.DEFAULT_MAX_TOKENS)
            .getOrNull() ?: return Result.success()

        val parsed = AiResponseParser.parse(generated, container.preferences.defaultCurrency) ?: return Result.success()
        if (parsed.transactionIntent) {
            container.onAiTransactionParsed(rawMessage, parsed)
        }
        return Result.success()
    }

    companion object {
        const val KEY_RAW_MESSAGE = "raw_message"
    }
}
