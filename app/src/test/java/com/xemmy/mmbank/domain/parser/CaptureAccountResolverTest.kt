package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Precedence for which account a capture lands in: an explicit rule pin beats
 * the account number printed in the message, which beats an inference from past
 * behaviour. Pure and tested on its own so the ordering is a stated decision
 * rather than something you have to reconstruct from `recordParsed`.
 */
class CaptureAccountResolverTest {

    @Test
    fun `a rule pin wins over both weaker signals`() {
        assertEquals(
            1L,
            CaptureAccountResolver.resolve(
                rulePinnedAccountId = 1,
                detectedAccountId = 2,
                learnedAccountId = 3,
            ),
        )
    }

    @Test
    fun `a detected account number wins over a learned merchant rule`() {
        // Hard evidence in the message text beats an inference from history.
        assertEquals(
            2L,
            CaptureAccountResolver.resolve(
                rulePinnedAccountId = null,
                detectedAccountId = 2,
                learnedAccountId = 3,
            ),
        )
    }

    @Test
    fun `a learned rule applies only when nothing else resolved`() {
        assertEquals(
            3L,
            CaptureAccountResolver.resolve(
                rulePinnedAccountId = null,
                detectedAccountId = null,
                learnedAccountId = 3,
            ),
        )
    }

    @Test
    fun `a rule pin still wins when the message carries no account number`() {
        assertEquals(
            1L,
            CaptureAccountResolver.resolve(
                rulePinnedAccountId = 1,
                detectedAccountId = null,
                learnedAccountId = 3,
            ),
        )
    }

    @Test
    fun `nothing resolves when there is no signal at all`() {
        assertNull(
            CaptureAccountResolver.resolve(
                rulePinnedAccountId = null,
                detectedAccountId = null,
                learnedAccountId = null,
            ),
        )
    }
}
