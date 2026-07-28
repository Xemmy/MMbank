package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Builds the natural-language financial Q&A prompt for the Copilot chat (see
 * ui/screens/copilot/CopilotChatScreen.kt): aggregates the user's own
 * transaction history into a compact structured context (totals by category
 * and merchant within the asked-about date range) and appends the user's
 * question, so the LLM answers from real numbers instead of guessing. Pure
 * and top-level so it's JVM-testable; the actual LLM call happens through
 * [com.xemmy.mmbank.data.ai.LocalAiManager] (see AppContainer), never here --
 * no raw transaction text or account numbers are ever included in the prompt.
 */
object FinancialCopilotQueryHandler {

    /** Aggregated numbers handed to the LLM as context. */
    data class QueryContext(
        val rangeLabel: String,
        val currencyCode: String,
        val totalIncome: Double,
        val totalExpenses: Double,
        /** category label -> total spend, descending. */
        val byCategory: Map<String, Double>,
        /** merchant -> total spend, descending. */
        val byMerchant: Map<String, Double>,
    )

    /**
     * Aggregates [transactions] -- already converted to the base currency via
     * [toBase] (a hook, not a hard FX dependency, same pattern as
     * [AiWeeklyDigestGenerator]) -- within [start]..[end] inclusive.
     * Analytics-excluded rows (balance corrections, transfers) are skipped,
     * same as every other stats surface in the app.
     */
    fun buildContext(
        transactions: List<Transaction>,
        toBase: (Transaction) -> Double?,
        start: LocalDate,
        end: LocalDate,
        currencyCode: String,
        rangeLabel: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): QueryContext {
        fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

        val inRange = transactions.filter { tx ->
            !tx.excludeFromStats && tx.timestampMillis.toLocalDate().let { d -> !d.isBefore(start) && !d.isAfter(end) }
        }

        val income = inRange.filter { it.direction == TransactionDirection.CREDIT }.sumOf { toBase(it) ?: 0.0 }
        val debits = inRange.filter { it.direction == TransactionDirection.DEBIT }
        val expenses = debits.sumOf { toBase(it) ?: 0.0 }

        val byCategory = debits
            .groupBy { it.spendingCategory.label }
            .mapValues { (_, txs) -> txs.sumOf { toBase(it) ?: 0.0 } }
            .filterValues { it > 0.0 }
            .toList()
            .sortedByDescending { it.second }
            .toMap()

        val byMerchant = debits
            .groupBy { it.merchant?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown" }
            .mapValues { (_, txs) -> txs.sumOf { toBase(it) ?: 0.0 } }
            .filterValues { it > 0.0 }
            .toList()
            .sortedByDescending { it.second }
            .toMap()

        return QueryContext(
            rangeLabel = rangeLabel,
            currencyCode = currencyCode.uppercase(),
            totalIncome = income,
            totalExpenses = expenses,
            byCategory = byCategory,
            byMerchant = byMerchant,
        )
    }

    /** Caps how many merchant lines are sent to keep the prompt compact. */
    private const val MAX_MERCHANT_LINES = 20

    /** Builds the constrained prompt sent to the LLM: structured numbers first, then the user's question. */
    fun buildPrompt(question: String, context: QueryContext): String = buildString {
        appendLine("You are a personal-finance assistant. Answer the user's question about their own spending")
        appendLine("using ONLY the numbers given below -- never invent figures. Keep the answer to 1-3 short,")
        appendLine("conversational sentences, formatting amounts as \"${context.currencyCode} X.XX\".")
        appendLine()
        appendLine("Period: ${context.rangeLabel}")
        appendLine("Total income: ${context.currencyCode} ${money(context.totalIncome)}")
        appendLine("Total expenses: ${context.currencyCode} ${money(context.totalExpenses)}")
        if (context.byCategory.isNotEmpty()) {
            appendLine("Spend by category:")
            context.byCategory.forEach { (name, amount) ->
                appendLine("- $name: ${context.currencyCode} ${money(amount)}")
            }
        }
        if (context.byMerchant.isNotEmpty()) {
            appendLine("Spend by merchant:")
            context.byMerchant.entries.take(MAX_MERCHANT_LINES).forEach { (name, amount) ->
                appendLine("- $name: ${context.currencyCode} ${money(amount)}")
            }
        }
        appendLine()
        appendLine("Question: $question")
    }

    private fun money(amount: Double): String = String.format(Locale.US, "%.2f", amount)
}
