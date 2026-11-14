package com.xemmy.mmbank.domain.parser

/**
 * Picks which account a newly captured transaction belongs to, from up to three
 * competing signals. The sibling of [CaptureCategoryResolver], and kept pure
 * for the same reason: the precedence is a decision worth testing on its own
 * rather than something buried inside a Room transaction.
 *
 * The order is strongest-evidence-first:
 *
 *  1. **A rule's "assign matches to" pin.** The user configured this rule to
 *     mean this account. Nothing outranks an explicit instruction.
 *  2. **The account number in the message.** Hard evidence, printed by the bank
 *     in the text itself.
 *  3. **A learned merchant rule.** An inference from how the user filed this
 *     merchant before — right often enough to be worth applying, but the
 *     weakest of the three, so it only speaks when the others are silent.
 */
object CaptureAccountResolver {
    fun resolve(
        rulePinnedAccountId: Long?,
        detectedAccountId: Long?,
        learnedAccountId: Long?,
    ): Long? = rulePinnedAccountId ?: detectedAccountId ?: learnedAccountId
}
