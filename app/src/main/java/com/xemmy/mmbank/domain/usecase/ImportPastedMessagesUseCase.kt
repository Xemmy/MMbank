package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.MessageDateExtractor
import com.xemmy.mmbank.domain.parser.PastedMessageSplitter
import com.xemmy.mmbank.domain.repository.TransactionRepository
import java.time.ZoneId

/**
 * Imports a blob of pasted bank messages — a backlog copied out of the SMS
 * app, typically — as if each had been captured live.
 *
 * Every message goes through the same [IngestCapturedMessageUseCase] the SMS
 * receiver and notification listener use, so rules, categorization and the
 * ignore list all behave identically; this class only decides what to feed it
 * and how to account for what came back.
 *
 * Two things differ from a live capture, both because a pasted message has no
 * arrival time of its own:
 *
 *  - **When it happened** comes from the date the bank printed in the text
 *    ([MessageDateExtractor]), falling back to [now] when there is none.
 *  - **Duplicate detection** leans on the exact message text
 *    ([TransactionRepository.findIdByRawMessage]), which is what makes
 *    re-pasting the same backlog a no-op. The ±2-minute sliding window only
 *    runs for messages that state a real clock time; for a date-only or
 *    undated message it would compare fabricated timestamps and could discard
 *    a genuine second purchase of the same amount on the same day.
 */
class ImportPastedMessagesUseCase(
    private val ingest: IngestCapturedMessageUseCase,
    private val transactionRepository: TransactionRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** What one paste did, in the terms the screen reports back to the user. */
    data class Summary(
        /** New transactions recorded. */
        val imported: Int = 0,
        /** Already in the ledger — same text, or the same purchase captured live. */
        val skippedDuplicates: Int = 0,
        /** Matched a muted template (see the ignore list). */
        val ignored: Int = 0,
        /** No rule recognized these as transactions. */
        val unparsed: Int = 0,
    ) {
        val total: Int get() = imported + skippedDuplicates + ignored + unparsed
    }

    suspend operator fun invoke(blob: String, now: Long): Summary {
        var summary = Summary()
        // Messages repeated inside one paste are the same message, so the
        // first one is imported and the rest report as duplicates -- which is
        // exactly what re-running the whole paste does too.
        val seen = mutableSetOf<String>()

        for (text in PastedMessageSplitter.split(blob)) {
            summary = if (!seen.add(text) || transactionRepository.findIdByRawMessage(text) != null) {
                summary.copy(skippedDuplicates = summary.skippedDuplicates + 1)
            } else {
                summary.fold(record(text, now))
            }
        }
        return summary
    }

    private suspend fun record(text: String, now: Long): IngestResult {
        val stated = MessageDateExtractor.extract(text, now = now, zone = zone)
        val message = CapturedMessage(
            source = TransactionSource.PASTED,
            sender = null,
            title = null,
            body = text,
            timestampMillis = stated?.epochMillis ?: now,
        )
        return ingest(message, dedupWindow = stated?.hasTimeOfDay == true)
    }

    private fun Summary.fold(result: IngestResult): Summary = when (result) {
        is IngestResult.Inserted -> copy(imported = imported + 1)
        is IngestResult.Duplicate -> copy(skippedDuplicates = skippedDuplicates + 1)
        IngestResult.Ignored -> copy(ignored = ignored + 1)
        IngestResult.NoMatch -> copy(unparsed = unparsed + 1)
    }
}
