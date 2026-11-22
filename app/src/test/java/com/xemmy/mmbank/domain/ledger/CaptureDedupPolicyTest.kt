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

    private val bank = Side(SMS, "Your card XXXX1111 was debited AED 168.41 at DU", null, merchant = "DU")
    private val receipt = Side(SMS, "Thank you for your payment of AED 168.41 for 0551234567", null, merchant = "du")

    @Test fun billReceiptAndBankSmsForOnePaymentAreOne() {
        assertTrue(same(bank, receipt))
        assertTrue(same(receipt, bank))
    }

    @Test fun twoNumberlessBankSmsFromOneProviderAreTwoPayments() {
        val other = Side(SMS, "Your card XXXX1111 was debited AED 168.41 at DU ref 2", null, merchant = "DU")
        assertFalse(same(bank, other))
    }

    @Test fun receiptsNamingDifferentLinesAreNeverMerged() {
        val lineB = Side(SMS, "Thank you for your payment of AED 168.41 for 0509998910", null, merchant = "du")
        assertFalse(same(receipt, lineB))
        assertFalse(CaptureDedupPolicy.isBillPair(receipt, lineB))
    }

    @Test fun differentProvidersAreNotABillPair() {
        val dewa = Side(SMS, "DEWA payment AED 168.41 for 0551234567", null, merchant = "DEWA")
        assertFalse(CaptureDedupPolicy.isBillPair(bank, dewa))
    }

    @Test fun aStoredLineOnTheExistingRowCounts() {
        val merged = bank.copy(billRef = "4567")
        val otherLine = Side(SMS, "Thank you for your payment of AED 168.41 for 0509998910", null, merchant = "du")
        assertFalse(CaptureDedupPolicy.isBillPair(merged, otherLine))
    }

    @Test fun arabicDigitEndingsAreTheSameCard() {
        assertFalse(CaptureDedupPolicy.endingsConflict("١١١١", "1111"))
        assertTrue(CaptureDedupPolicy.endingsConflict("١١١١", "2222"))
    }

    @Test fun aBankSmsNamingTheBankAccountStillPairsWithTheReceipt() {
        val bankWithAccount = Side(SMS, "AED 168.41 debited from your account 1234567890 for DU", null, merchant = "DU")
        assertTrue(CaptureDedupPolicy.isBillPair(bankWithAccount, receipt))
    }

    // --- 0.140.2: a delayed SMS and the SMS app's notification copy ---

    private val hour = 60L * 60 * 1000
    private val smsText = "AED 52.00 spent at CARREFOUR with card XXXX1111. Avl Bal AED 1,200.00"

    @Test fun anSmsAndItsMessagingAppCopyHoursLaterAreOne() {
        val sms = Side(SMS, smsText, "1111")
        val push = Side(PUSH_NOTIFICATION, "  $smsText ", "1111")
        // The SMS carries the carrier's send time; the notification its (late) delivery time.
        assertTrue(CaptureDedupPolicy.isDuplicate(sms, 0L, push, 3 * hour))
        assertTrue(CaptureDedupPolicy.isDuplicate(push, 3 * hour, sms, 0L))
    }

    @Test fun aNotificationEarlierThanTheSmsIsNotItsCopy() {
        val sms = Side(SMS, smsText, null)
        val push = Side(PUSH_NOTIFICATION, smsText, null)
        assertFalse(CaptureDedupPolicy.isDuplicate(push, 0L, sms, 3 * hour))
    }

    @Test fun theMirrorNeedsIdenticalTextAndStaysInsideTwelveHours() {
        val sms = Side(SMS, smsText, null)
        assertFalse(CaptureDedupPolicy.isDuplicate(sms, 0L, Side(PUSH_NOTIFICATION, "Bank app: AED 52.00 at Carrefour", null), 3 * hour))
        assertFalse(CaptureDedupPolicy.isDuplicate(sms, 0L, Side(PUSH_NOTIFICATION, smsText, null), 13 * hour))
    }

    @Test fun twoIdenticalSmsHoursApartAreTwoPurchases() {
        val toll = "Salik: AED 4.00 deducted"
        assertFalse(CaptureDedupPolicy.isDuplicate(Side(SMS, toll, null), 0L, Side(SMS, toll, null), 3 * hour))
    }

    // --- 0.140.2: bill pairs never swallow a second payment ---

    @Test fun anAlreadyPairedBankSmsDoesNotTakeAnotherLinesSms() {
        // Line A's bank SMS took the receipt's line; line B's bank SMS (no number) is another payment.
        val pairedA = bank.copy(billRef = "4567")
        val lineB = Side(SMS, "Your card XXXX1111 was debited AED 168.41 at DU on 21/11", null, merchant = "DU")
        assertFalse(CaptureDedupPolicy.isBillPair(pairedA, lineB))
        assertFalse(CaptureDedupPolicy.isDuplicate(pairedA, 0L, lineB, 2 * hour))
    }

    @Test fun twoReceiptsFromTheProviderForOneLineAreTwoPayments() {
        val first = receipt.copy(sender = "du")
        val second = Side(SMS, "Thank you for your payment of AED 168.41 for 0551234567. Ref 2", null, merchant = "du", sender = "DU")
        assertFalse(CaptureDedupPolicy.isBillPair(first, second))
    }

    @Test fun theBankAndTheProviderNamingTheSameLineAreOnePayment() {
        val bankNamed = Side(SMS, "AED 168.41 paid to du for 0551234567 from card XXXX1111", null, merchant = "du", sender = "ADCB")
        assertTrue(CaptureDedupPolicy.isBillPair(bankNamed, receipt.copy(sender = "du")))
    }

    @Test fun billPaymentsFromTwoCardsAreTwoPayments() {
        assertFalse(CaptureDedupPolicy.isBillPair(bank.copy(cardEnding = "1111"), receipt.copy(cardEnding = "2222")))
    }

    @Test fun theUsualBankThenReceiptPairStillMergesAfterHours() {
        assertTrue(CaptureDedupPolicy.isDuplicate(bank.copy(sender = "ADCB"), 0L, receipt.copy(sender = "du"), 5 * hour))
    }
}
