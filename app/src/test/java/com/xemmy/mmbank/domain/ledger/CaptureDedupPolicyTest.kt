package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.CaptureDedupPolicy.Side
import com.xemmy.mmbank.domain.model.TransactionSource.PUSH_NOTIFICATION
import com.xemmy.mmbank.domain.model.TransactionSource.SMS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDedupPolicyTest {
    private fun same(a: Side, b: Side) = CaptureDedupPolicy.isSameTransaction(a, b)

    @Test fun smsAndPushForOnePurchaseAreDuplicates() {
        assertTrue(same(Side(SMS, "AED 4 at Salik", null), Side(PUSH_NOTIFICATION, "Salik AED 4.00", null)))
    }

    @Test fun twoSmsWithDifferentTextAreTwoPurchases() {
        assertFalse(same(Side(SMS, "Salik gate 1 AED 4", null), Side(SMS, "Salik gate 2 AED 4", null)))
    }

    @Test fun identicalTextOnTheSameChannelIsARedelivery() {
        assertTrue(same(Side(SMS, " AED 4 at Salik ", null), Side(SMS, "AED 4 at Salik", null)))
    }

    @Test fun sameChannelWithNoTextIsNeverMerged() {
        assertFalse(same(Side(SMS, null, null), Side(SMS, null, null)))
    }

    @Test fun differentKnownCardEndingsAreNeverDuplicates() {
        assertFalse(same(Side(SMS, "a", "1234"), Side(PUSH_NOTIFICATION, "b", "9999")))
    }

    @Test fun matchingOrMissingEndingsDoNotVeto() {
        assertTrue(same(Side(SMS, "a", "1234"), Side(PUSH_NOTIFICATION, "b", "1234")))
        assertTrue(same(Side(SMS, "a", "1234"), Side(PUSH_NOTIFICATION, "b", null)))
        assertTrue(same(Side(SMS, "a", "**1234"), Side(PUSH_NOTIFICATION, "b", "1234")))
    }
}
