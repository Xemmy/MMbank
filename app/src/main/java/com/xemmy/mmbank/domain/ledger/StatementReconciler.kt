package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Diffs a bank statement against what MMbank captured, line by line (backlog
 * B.5 #3) -- the one check that finds a transaction the capture never saw.
 *
 * Each statement line is paired with at most one ledger row, and each ledger
 * row with at most one line: same signed amount, dates within
 * [dayTolerance] days (banks post a day or two after the SMS), the nearest
 * date first, then the most similar description. What is left over on either
 * side is the answer:
 *  - [Result.missing]: on the statement, not in MMbank -- capture missed it;
 *  - [Result.extra]: in MMbank within the statement's dates, not on the
 *    statement -- a duplicate, a mis-parse, or something still pending.
 *
 * Pure: the caller passes only the rows of the one account being checked.
 */
object StatementReconciler {

    data class Match(val line: StatementLine, val transaction: Transaction)

    data class Result(
        val matched: List<Match>,
        val missing: List<StatementLine>,
        val extra: List<Transaction>,
    ) {
        val isClean: Boolean get() = missing.isEmpty() && extra.isEmpty()
    }

    const val DEFAULT_DAY_TOLERANCE = 3

    fun reconcile(
        lines: List<StatementLine>,
        transactions: List<Transaction>,
        dayTolerance: Int = DEFAULT_DAY_TOLERANCE,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        // Balance corrections are bookkeeping, not bank movements: they would only ever show as "extra".
        // Rows are tracked by position, not equality: two identical purchases are two rows.
        val candidates = transactions.filter { it.kind != TransactionKind.ADJUSTMENT }
        val taken = BooleanArray(candidates.size)
        val matched = mutableListOf<Match>()
        val missing = mutableListOf<StatementLine>()

        for (line in lines.sortedBy { it.date }) {
            val bestIndex = candidates.indices
                .asSequence()
                .filter { !taken[it] && signedAmount(candidates[it]).compareTo(line.amount) == 0 }
                .map { it to abs(ChronoUnit.DAYS.between(dateOf(candidates[it], zone), line.date)) }
                .filter { (_, days) -> days <= dayTolerance }
                .sortedWith(
                    compareBy<Pair<Int, Long>> { it.second }
                        .thenByDescending { similarity(line.description, candidates[it.first].merchant) },
                )
                .firstOrNull()
                ?.first
            if (bestIndex == null) {
                missing += line
            } else {
                taken[bestIndex] = true
                matched += Match(line, candidates[bestIndex])
            }
        }

        val from = lines.minOfOrNull { it.date }?.minusDays(dayTolerance.toLong())
        val to = lines.maxOfOrNull { it.date }?.plusDays(dayTolerance.toLong())
        val extra = if (from == null || to == null) {
            emptyList()
        } else {
            candidates.filterIndexed { i, tx -> !taken[i] && dateOf(tx, zone) in from..to }.sortedBy { it.timestampMillis }
        }
        return Result(matched, missing, extra)
    }

    /** Money in positive, money out negative -- the statement's convention. */
    internal fun signedAmount(tx: Transaction) =
        if (tx.direction == TransactionDirection.CREDIT) tx.amount else tx.amount.negate()

    private fun dateOf(tx: Transaction, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()

    /** Words in common between the statement text and the merchant; 0 when either is empty. */
    internal fun similarity(description: String, merchant: String?): Int {
        if (merchant.isNullOrBlank() || description.isBlank()) return 0
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9\\u0600-\\u06FF]+")).filter { it.length >= 3 }.toSet()
        return words(description).intersect(words(merchant)).size
    }
}
