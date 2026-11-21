package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.parser.MerchantAccountLearner.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which already-captured transactions a freshly learned merchant -> account
 * rule reaches backwards into. The caller only ever supplies rows that are
 * already unassigned, so this can never overwrite an account the user chose --
 * what it still has to get right is not reaching rows from a *different*
 * merchant.
 */
class MerchantAccountLearnerTest {

    @Test
    fun `an exact merchant match is backfilled`() {
        val ids = MerchantAccountLearner.idsToBackfill(
            listOf(Candidate(1, "Golden Fork"), Candidate(2, "Spinneys")),
            "Golden Fork",
        )
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `matching is case-insensitive and ignores surrounding whitespace`() {
        val ids = MerchantAccountLearner.idsToBackfill(
            listOf(Candidate(1, "  golden fork ")),
            "GOLDEN FORK",
        )
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `a differently-formatted spelling of the same merchant is backfilled`() {
        // The reason the normalizer exists: the rule is learned from one
        // message's spelling and the history is full of others.
        val ids = MerchantAccountLearner.idsToBackfill(
            listOf(
                Candidate(1, "POS CARREFOUR HYPERMARKET DXB *4543"),
                Candidate(2, "CARREFOUR MARKET JUMEIRAH"),
            ),
            "Carrefour",
        )
        assertEquals(listOf(1L, 2L), ids)
    }

    @Test
    fun `a different merchant is never backfilled`() {
        val ids = MerchantAccountLearner.idsToBackfill(
            listOf(Candidate(1, "Spinneys Market"), Candidate(2, "Choithram")),
            "Carrefour",
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `two unkeyable merchants never match each other`() {
        // Both key to "", which must not be treated as a shared identity --
        // a wrong account moves money between balances.
        val ids = MerchantAccountLearner.idsToBackfill(listOf(Candidate(1, "##")), "@@")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `an unkeyable merchant still backfills its own exact spelling`() {
        val ids = MerchantAccountLearner.idsToBackfill(listOf(Candidate(1, "##")), "##")
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `a blank merchant reaches nothing`() {
        val ids = MerchantAccountLearner.idsToBackfill(listOf(Candidate(1, "Golden Fork")), "   ")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `an empty candidate list plans nothing`() {
        assertTrue(MerchantAccountLearner.idsToBackfill(emptyList(), "Carrefour").isEmpty())
    }

    @Test
    fun `a brand's other branches are reached but an unrelated brand is not`() {
        val ids = MerchantAccountLearner.idsToBackfill(
            listOf(
                Candidate(1, "ADNOC STATION 1042"),
                Candidate(2, "ADNOC DUBAI"),
                Candidate(3, "ENOC STATION 12"),
            ),
            "ADNOC",
        )
        assertEquals(listOf(1L, 2L), ids)
    }
}
