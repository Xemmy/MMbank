package com.xemmy.mmbank.domain.ai

/**
 * The retention prune deletes transactions for good, so it must never run
 * without a way back (Improvement Plan B.2): it only deletes when an
 * encrypted backup was exported within [MAX_BACKUP_AGE_DAYS] days. Pure, so
 * the weekly worker and the Settings preview share one rule.
 *
 * Only the encrypted backup counts -- a CSV export can't restore anything.
 */
object RetentionBackupGate {

    const val MAX_BACKUP_AGE_DAYS = 30
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    enum class Status { FRESH, STALE, NEVER }

    /** [lastBackupAtMillis] of 0 (or less) means no backup was ever exported. */
    fun status(lastBackupAtMillis: Long, nowMillis: Long): Status = when {
        lastBackupAtMillis <= 0L -> Status.NEVER
        // A clock that moved backwards must not leave the prune blocked forever.
        lastBackupAtMillis >= nowMillis -> Status.FRESH
        daysSince(lastBackupAtMillis, nowMillis) <= MAX_BACKUP_AGE_DAYS -> Status.FRESH
        else -> Status.STALE
    }

    fun allowsPrune(status: Status): Boolean = status == Status.FRESH

    fun daysSince(lastBackupAtMillis: Long, nowMillis: Long): Int =
        ((nowMillis - lastBackupAtMillis).coerceAtLeast(0L) / DAY_MILLIS).toInt()

    /** One sentence for the preview under the retention chips. */
    fun describe(status: Status, lastBackupAtMillis: Long, nowMillis: Long): String = when (status) {
        Status.FRESH -> "Your last backup is recent, so the prune will run."
        Status.STALE ->
            "Pruning is paused: your last backup is ${daysSince(lastBackupAtMillis, nowMillis)} days old. " +
                "Export a backup (Backup & restore) to let it run."
        Status.NEVER -> "Pruning is paused until you export an encrypted backup (Backup & restore)."
    }
}
