package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.TransactionKindDetector.Input
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection.CREDIT
import com.xemmy.mmbank.domain.model.TransactionDirection.DEBIT
import com.xemmy.mmbank.domain.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionKindDetectorTest {

    private fun detect(input: Input) = TransactionKindDetector.detect(input)

    @Test
    fun `plain debit is spend and plain credit is income`() {
        assertEquals(TransactionKind.SPEND, detect(Input(DEBIT, rawMessage = "AED 45.00 spent at Carrefour")))
        assertEquals(TransactionKind.INCOME, detect(Input(CREDIT, rawMessage = "Salary AED 15000 credited to your account")))
    }

    @Test
    fun `ATM withdrawals are recognized`() {
        assertEquals(TransactionKind.ATM, detect(Input(DEBIT, rawMessage = "AED 500.00 withdrawn from ATM at Dubai Mall")))
        assertEquals(TransactionKind.ATM, detect(Input(DEBIT, rawMessage = "Cash withdrawal of AED 200 from account *617")))
        assertEquals(TransactionKind.ATM, detect(Input(DEBIT, rawMessage = "تم سحب نقدي بمبلغ 300 درهم")))
    }

    @Test
    fun `refunds and reversals on a credit are refunds, not income`() {
        assertEquals(TransactionKind.REFUND, detect(Input(CREDIT, rawMessage = "Refund of AED 120.00 from Amazon credited")))
        assertEquals(TransactionKind.REFUND, detect(Input(CREDIT, rawMessage = "Transaction reversal AED 35.00 Noon")))
    }

    @Test
    fun `refund wording on a debit is not a refund`() {
        assertEquals(TransactionKind.SPEND, detect(Input(DEBIT, rawMessage = "Purchase AED 20 at Refund Bar")))
    }

    @Test
    fun `a card payment debit from the bank account is a card payment`() {
        assertEquals(
            TransactionKind.CARD_PAYMENT,
            detect(Input(DEBIT, rawMessage = "AED 3600.00 debited from your account *963 for credit card payment")),
        )
        assertEquals(
            TransactionKind.CARD_PAYMENT,
            detect(Input(DEBIT, rawMessage = "Payment to card ending 876 of AED 1000 successful")),
        )
    }

    @Test
    fun `the card-payment credit leg is a card payment`() {
        val msg = "Dear Customer, your payment of AED3600.00 on 7/20/2026 for card ending with *876 has been credited. Thank you"
        assertEquals(TransactionKind.CARD_PAYMENT, detect(Input(CREDIT, rawMessage = msg)))
        assertEquals(
            TransactionKind.CARD_PAYMENT,
            detect(Input(CREDIT, matchedRuleName = "builtin.en.transfer.card-payment-credit", rawMessage = "x")),
        )
    }

    @Test
    fun `any unexplained credit landing on a credit card is a payment, never income`() {
        assertEquals(
            TransactionKind.CARD_PAYMENT,
            detect(Input(CREDIT, rawMessage = "AED 500 credited", accountType = AccountType.CREDIT_CARD)),
        )
        // ...but a recognizable refund on the card stays a refund.
        assertEquals(
            TransactionKind.REFUND,
            detect(Input(CREDIT, rawMessage = "Refund AED 50 IKEA", accountType = AccountType.CREDIT_CARD)),
        )
    }

    @Test
    fun `own-account transfers are transfers`() {
        assertEquals(
            TransactionKind.TRANSFER,
            detect(Input(DEBIT, rawMessage = "AED 2000 transferred to your savings account *1122")),
        )
        assertEquals(
            TransactionKind.TRANSFER,
            detect(Input(CREDIT, rawMessage = "Internal transfer of AED 2000 received")),
        )
    }

    @Test
    fun `Arabic transfer-shaped rules are not assumed to be own transfers`() {
        // builtin.ar.transfer.in also catches salary and other incoming money.
        assertEquals(
            TransactionKind.INCOME,
            detect(Input(CREDIT, matchedRuleName = "builtin.ar.transfer.in", rawMessage = "AED62350.00 إلى حسابك رقم XXX810001")),
        )
    }

    @Test
    fun `Tabby adjustments - decrease is a refund, increase is spend`() {
        assertEquals(TransactionKind.REFUND, detect(Input(CREDIT, matchedRuleName = "builtin.en.adjust.decrease")))
        assertEquals(TransactionKind.SPEND, detect(Input(DEBIT, matchedRuleName = "builtin.en.adjust.increase")))
    }

    @Test
    fun `cashback tag wins over wording`() {
        assertEquals(
            TransactionKind.CASHBACK,
            detect(Input(CREDIT, rawMessage = "You earned AED 1.00!", category = TransactionCategory.CASHBACK_EARNED)),
        )
    }

    @Test
    fun `a rule's explicit kind wins over everything`() {
        assertEquals(
            TransactionKind.TRANSFER,
            detect(Input(DEBIT, rawMessage = "ATM withdrawal AED 100", ruleKind = TransactionKind.TRANSFER)),
        )
    }

    @Test
    fun `a learned merchant kind wins over wording but not over the rule`() {
        assertEquals(
            TransactionKind.CARD_PAYMENT,
            detect(Input(DEBIT, rawMessage = "AED 900 paid to ADCB CC", learnedKind = TransactionKind.CARD_PAYMENT)),
        )
        assertEquals(
            TransactionKind.SPEND,
            detect(Input(DEBIT, ruleKind = TransactionKind.SPEND, learnedKind = TransactionKind.CARD_PAYMENT)),
        )
    }

    @Test
    fun `a one-sided kind never lands on the wrong direction`() {
        assertEquals(TransactionKind.INCOME, detect(Input(CREDIT, ruleKind = TransactionKind.SPEND)))
        assertEquals(TransactionKind.SPEND, detect(Input(DEBIT, learnedKind = TransactionKind.REFUND)))
        assertEquals(TransactionKind.TRANSFER, TransactionKindDetector.coerce(TransactionKind.TRANSFER, CREDIT))
        assertEquals(TransactionKind.ADJUSTMENT, TransactionKindDetector.coerce(TransactionKind.ADJUSTMENT, DEBIT))
    }

    @Test
    fun `legacy rows - automatic exclusions become kinds and drop the flag`() {
        val linkedOnCard = TransactionKindDetector.fromLegacy(
            direction = DEBIT, matchedRuleName = null, merchant = null,
            category = TransactionCategory.NORMAL, excludeFromStats = true, linked = true, onCreditCard = true,
        )
        assertEquals(TransactionKind.CARD_PAYMENT to false, linkedOnCard)

        val linkedPlain = TransactionKindDetector.fromLegacy(
            direction = CREDIT, matchedRuleName = null, merchant = null,
            category = TransactionCategory.NORMAL, excludeFromStats = true, linked = true, onCreditCard = false,
        )
        assertEquals(TransactionKind.TRANSFER to false, linkedPlain)

        val adjustment = TransactionKindDetector.fromLegacy(
            direction = CREDIT, matchedRuleName = "Balance adjustment", merchant = "Balance adjustment",
            category = TransactionCategory.NORMAL, excludeFromStats = true, linked = false, onCreditCard = false,
        )
        assertEquals(TransactionKind.ADJUSTMENT to false, adjustment)

        val tabbyRefund = TransactionKindDetector.fromLegacy(
            direction = CREDIT, matchedRuleName = "builtin.en.adjust.decrease", merchant = "Noon",
            category = TransactionCategory.NORMAL, excludeFromStats = true, linked = false, onCreditCard = true,
        )
        assertEquals(TransactionKind.REFUND to false, tabbyRefund)
    }

    @Test
    fun `legacy rows - a flag the user set by hand survives`() {
        val (kind, excluded) = TransactionKindDetector.fromLegacy(
            direction = DEBIT, matchedRuleName = "builtin.en.debit.cur-amt", merchant = "Gift for mum",
            category = TransactionCategory.NORMAL, excludeFromStats = true, linked = false, onCreditCard = false,
        )
        assertEquals(TransactionKind.SPEND, kind)
        assertTrue(excluded)

        val (cashback, cashbackExcluded) = TransactionKindDetector.fromLegacy(
            direction = CREDIT, matchedRuleName = null, merchant = null,
            category = TransactionCategory.CASHBACK_EARNED, excludeFromStats = false, linked = false, onCreditCard = false,
        )
        assertEquals(TransactionKind.CASHBACK, cashback)
        assertFalse(cashbackExcluded)
    }

    private fun row(
        direction: com.xemmy.mmbank.domain.model.TransactionDirection,
        raw: String,
        kind: TransactionKind = TransactionKind.defaultFor(direction),
        kindUserSet: Boolean = false,
        linkedTransactionId: Long? = null,
    ) = com.xemmy.mmbank.domain.model.Transaction(
        amount = java.math.BigDecimal.TEN,
        currencyCode = "AED",
        direction = direction,
        source = com.xemmy.mmbank.domain.model.TransactionSource.SMS,
        rawMessage = raw,
        timestampMillis = 0,
        kind = kind,
        kindUserSet = kindUserSet,
        linkedTransactionId = linkedTransactionId,
    )

    @Test
    fun `reclassify upgrades a plain row the SQL migration could not read`() {
        assertEquals(TransactionKind.ATM, TransactionKindDetector.reclassify(row(DEBIT, "ATM withdrawal AED 200"), null))
        assertEquals(TransactionKind.REFUND, TransactionKindDetector.reclassify(row(CREDIT, "Refund AED 20 Noon"), null))
    }

    @Test
    fun `reclassify leaves user-set, linked, already-classified and unchanged rows alone`() {
        assertEquals(null, TransactionKindDetector.reclassify(row(DEBIT, "ATM withdrawal", kindUserSet = true), null))
        assertEquals(null, TransactionKindDetector.reclassify(row(DEBIT, "ATM withdrawal", linkedTransactionId = 9), null))
        assertEquals(
            null,
            TransactionKindDetector.reclassify(row(DEBIT, "ATM withdrawal", kind = TransactionKind.ADJUSTMENT), null),
        )
        assertEquals(null, TransactionKindDetector.reclassify(row(DEBIT, "Spent AED 20 at Lulu"), null))
    }

    @Test
    fun `money transferred to your account from someone else stays income`() {
        assertEquals(
            TransactionKind.INCOME,
            detect(Input(CREDIT, rawMessage = "AED 500.00 transferred to your account *1122 by JOHN")),
        )
    }

    @Test
    fun `a card payment at a merchant is still a purchase`() {
        assertEquals(
            TransactionKind.SPEND,
            detect(Input(DEBIT, rawMessage = "Card payment of AED 80.00 at SPINNEYS")),
        )
    }

    @Test
    fun `a transfer from your savings credit is an own-account transfer`() {
        assertEquals(
            TransactionKind.TRANSFER,
            detect(Input(CREDIT, rawMessage = "AED 900 transferred from your savings account")),
        )
    }
}
