package com.xemmy.mmbank.domain.onboarding

/**
 * The "Get started" checklist on Home (Phase 3 / onboarding): four things that
 * make the difference between an app that quietly fills itself in and an empty
 * one. Every step is derived from real state, never from a "mark done" tap, so
 * it can't be wrong and it ticks itself off as the user does things.
 *
 * The card shows until every step is done or the user dismisses it; once
 * dismissed it stays away. Pure and JVM-testable.
 */
object OnboardingChecklist {

    enum class StepId { ALLOW_CAPTURE, ADD_ACCOUNT, FIRST_TRANSACTION, BACK_UP }

    data class Step(val id: StepId, val title: String, val detail: String, val done: Boolean)

    data class Inputs(
        /** Notification access or SMS permission granted -- either lets MMbank see bank messages. */
        val captureAllowed: Boolean,
        val accountCount: Int,
        val transactionCount: Int,
        /** An encrypted backup was exported at some point (see `AppPreferences.lastBackupAt`). */
        val hasBackedUp: Boolean,
    )

    data class Checklist(val steps: List<Step>) {
        val doneCount: Int get() = steps.count { it.done }
        val complete: Boolean get() = steps.all { it.done }

        /** The first step still to do, which the card highlights. */
        val next: Step? get() = steps.firstOrNull { !it.done }
    }

    fun build(inputs: Inputs): Checklist = Checklist(
        listOf(
            Step(
                StepId.ALLOW_CAPTURE,
                "Let MMbank read your bank messages",
                "Allow notification access or SMS so transactions appear on their own.",
                inputs.captureAllowed,
            ),
            Step(
                StepId.ADD_ACCOUNT,
                "Add your first account",
                "Accounts keep balances right and let captured messages find their home.",
                inputs.accountCount > 0,
            ),
            Step(
                StepId.FIRST_TRANSACTION,
                "See your first transaction",
                "It shows up here once a bank message arrives -- or add a cash spend yourself.",
                inputs.transactionCount > 0,
            ),
            Step(
                StepId.BACK_UP,
                "Make a backup",
                "An encrypted copy you can restore from. Also what lets old history be pruned safely.",
                inputs.hasBackedUp,
            ),
        ),
    )

    /** Show the card only while there is something left to do and the user hasn't waved it away. */
    fun shouldShow(dismissed: Boolean, checklist: Checklist): Boolean = !dismissed && !checklist.complete
}
