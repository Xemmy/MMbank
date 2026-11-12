package com.xemmy.mmbank.data.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.ai.RetentionBackupGate
import com.xemmy.mmbank.domain.ai.TransactionRetentionPlanner

/**
 * Opt-in weekly prune of old, unremarkable transaction history, per
 * [com.xemmy.mmbank.data.prefs.AppPreferences.transactionRetentionMonths].
 *
 * One of MMbank's periodic work items -- alongside
 * [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker] and
 * [com.xemmy.mmbank.data.bills.BillReminderWorker] -- and, like those, only
 * exists while its Settings toggle is on (default off): a single local DB
 * read/plan/delete, no network, nothing scheduled for anyone who hasn't
 * asked for it.
 *
 * Deletion decisions are entirely [TransactionRetentionPlanner]'s (which in
 * turn defers "is this row worth keeping regardless of age" to
 * [com.xemmy.mmbank.domain.ai.AiRetentionScorer]) -- this class only supplies
 * the real transaction snapshot and the wall clock, then hands the plan to
 * [RetentionPruner], which is what keeps the deletion from moving a balance.
 *
 * Fails silently (`Result.success` with no side effect) on every non-fatal
 * condition, like [com.xemmy.mmbank.data.ai.AiParsingWorker] and
 * [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker] -- a missed
 * prune must never become a retry storm, and a failure here must never risk
 * a partial or repeated deletion.
 */
class TransactionRetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? MMBankApp)?.container ?: return Result.success()
        val retentionMonths = container.preferences.transactionRetentionMonths
        if (retentionMonths <= 0) return Result.success()

        runCatching {
            val transactions = container.transactionRepository.getAllReal()
            val plan = TransactionRetentionPlanner.plan(
                transactions = transactions,
                retentionMonths = retentionMonths,
                nowMillis = System.currentTimeMillis(),
            )
            // Deleting is permanent, so it needs a recent encrypted backup to fall back on;
            // without one this run does nothing and the Settings preview says why.
            val now = System.currentTimeMillis()
            val backup = RetentionBackupGate.status(container.preferences.lastBackupAt, now)
            if (plan.transactionIdsToDelete.isNotEmpty() && RetentionBackupGate.allowsPrune(backup)) {
                // Not a plain deleteMany: balances are derived from these rows,
                // so deleting them would silently shift every balance they fed.
                // RetentionPruner folds that net into the accounts' opening
                // balances in the same Room transaction as the delete.
                container.retentionPruner.prune(plan.transactionIdsToDelete)
            }
        }.onFailure { container.recordError("TransactionRetentionWorker", it) }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "transaction_retention_prune"
    }
}
