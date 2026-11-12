package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.Transaction
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure "what would this prune delete?" summary shown BEFORE retention is
 * switched on (Improvement Plan B.2), built from the same
 * [TransactionRetentionPlanner.Plan] the weekly worker executes.
 */
object RetentionPreview {

    data class Summary(val count: Int, val countByYear: Map<Int, Int>)

    fun summarize(plan: TransactionRetentionPlanner.Plan, transactions: List<Transaction>): Summary {
        val doomed = plan.transactionIdsToDelete.toSet()
        val byYear = transactions
            .filter { it.id in doomed }
            .groupingBy { Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneOffset.UTC).year }
            .eachCount()
            .toSortedMap()
        return Summary(count = doomed.size, countByYear = byYear)
    }

    /**
     * [backup], when given, is the [RetentionBackupGate] verdict with the
     * time it was reached from; it replaces the generic "back up first"
     * advice with the real state (the prune is paused until a fresh backup).
     */
    fun describe(summary: Summary, backup: BackupContext? = null): String {
        if (summary.count == 0) return "Nothing would be pruned right now."
        val noun = if (summary.count == 1) "transaction" else "transactions"
        val years = summary.countByYear.keys
        val span = if (years.size == 1) "from ${years.first()}" else "from ${years.first()}–${years.last()}"
        val head = "This would delete ${"%,d".format(summary.count)} $noun $span."
        return if (backup == null) {
            "$head Back up first if you want to keep them."
        } else {
            "$head ${RetentionBackupGate.describe(backup.status, backup.lastBackupAtMillis, backup.nowMillis)}"
        }
    }

    data class BackupContext(val status: RetentionBackupGate.Status, val lastBackupAtMillis: Long, val nowMillis: Long)
}
