package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 test matrix for signature-based routing, written against the
 * literal notification strings in the 99123.jpg reference:
 *
 *  - Tabby: title "Transaction of AED 46.12", body "At Tesla Motors Netherlan
 *    was successful. Your available Tabby Card limit is AED 5125.48."
 *    (spaced amount form, "At …" merchant clause)
 *  - Binance Mastercard: title "CARREFOUR-MAF MKT YAS", body "AED229.59 with
 *    Binance Mastercard ••2957" (non-spaced amount form, merchant is the
 *    notification title — the plan's "prominent app header" fallback — plus
 *    a card suffix)
 *
 * Note: the plan's 2.4 attributed the amount forms the other way around
 * (non-spaced to Tabby, spaced to Binance); the actual image is authoritative
 * and both forms are covered either way.
 */
class SignatureRoutingTest {

    private fun msg(body: String, sender: String? = "com.some.bank", title: String? = null) =
        CapturedMessage(
            source = TransactionSource.PUSH_NOTIFICATION,
            sender = sender,
            title = title,
            body = body,
            timestampMillis = 1_700_000_000_000,
        )

    private fun sigRule(
        name: String,
        signatures: List<String>,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        priority: Int = 100,
        assignAccountId: Long? = null,
        senderPattern: String? = null,
    ) = CompiledRule(
        ruleId = null,
        name = name,
        pattern = null,
        senderPattern = senderPattern?.let { Regex(it, RegexOption.IGNORE_CASE) },
        direction = direction,
        defaultCurrency = null,
        priority = priority,
        assignAccountId = assignAccountId,
        signatures = signatures,
    )

    // --- 1. Tabby limit notification (verbatim): routes by signature ---

    @Test
    fun `tabby limit signature routes with account and direction, spaced amount, at-clause merchant`() {
        val tabbyCard = sigRule(
            name = "sig.tabby.card",
            signatures = listOf("Your available Tabby Card limit"),
            direction = TransactionDirection.DEBIT,
            priority = 100,
            assignAccountId = 11L,
        )
        val parser = TransactionParser(listOf(tabbyCard) + BuiltInRules.all, "USD")

        val r = parser.parse(
            msg(
                "At Tesla Motors Netherlan was successful. Your available Tabby Card limit is AED 5125.48.",
                sender = "com.tabby.customer",
                title = "Transaction of AED 46.12",
            )
        )
        assertNotNull(r)
        // Signature routing bound the message to the Tabby rule, skipping the
        // built-in Tabby regex that also matches this text.
        assertEquals("sig.tabby.card", r!!.matchedRuleName)
        assertEquals(11L, r.assignAccountId)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        // The purchase amount from the title ("AED 46.12", spaced form), not
        // the remaining card limit later in the body.
        assertEquals(BigDecimal("46.12"), r.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals("Tesla Motors Netherlan", r.merchant)
    }

    // --- 2. Binance Mastercard notification (verbatim): built-in rule ---

    @Test
    fun `binance mastercard signature routes via built-in rule, non-spaced amount, title merchant`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")

        val r = parser.parse(
            msg(
                "AED229.59 with Binance Mastercard ••2957",
                sender = "com.google.android.apps.walletnfcrel",
                title = "CARREFOUR-MAF MKT YAS",
            )
        )
        assertNotNull(r)
        assertEquals("builtin.sig.binance.card", r!!.matchedRuleName)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        // Non-spaced "AED229.59" form; "2957" is a card suffix, not an amount.
        assertEquals(BigDecimal("229.59"), r.amount)
        assertEquals("AED", r.currencyCode)
        // No "at …" clause in this shape: the merchant IS the notification
        // title (the plan's prominent-app-header fallback).
        assertEquals("CARREFOUR-MAF MKT YAS", r.merchant)
        assertEquals("2957", r.accountNumberSuffix)
    }

    @Test
    fun `binance mastercard shape with at-clause still prefers the clause merchant`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")
        val r = parser.parse(
            msg("Binance Mastercard: Your transaction of AED 46.12 at CARREFOUR CITY MALL was approved.")
        )
        assertNotNull(r)
        assertEquals("builtin.sig.binance.card", r!!.matchedRuleName)
        assertEquals(BigDecimal("46.12"), r.amount)
        assertEquals("CARREFOUR CITY MALL", r.merchant)
    }

    // The signature rule's own direction is a single static default (DEBIT
    // for "Binance Mastercard"), which is wrong for a refund/credit routed
    // through the same card — the message wording must be able to override it.
    @Test
    fun `binance mastercard signature direction is overridden when the text says received`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")
        val r = parser.parse(msg("Binance Mastercard: You received a refund of AED 46.12"))
        assertNotNull(r)
        assertEquals("builtin.sig.binance.card", r!!.matchedRuleName)
        assertEquals(TransactionDirection.CREDIT, r.direction)
        assertEquals(BigDecimal("46.12"), r.amount)
    }

    // --- 3. Collision rules: priority, then signature length, then registration order ---

    @Test
    fun `collision - higher priority rule wins`() {
        // NOTE: the existing engine convention is HIGHER priority number wins
        // (custom rules at 100 outrank built-ins at 0-20); signature collisions
        // follow the same convention.
        val low = sigRule("sig.low", listOf("ALPHACARD"), priority = 10)
        val high = sigRule("sig.high", listOf("BETACARD"), priority = 20)
        val parser = TransactionParser(listOf(low, high), "USD")

        val r = parser.parse(msg("ALPHACARD and BETACARD both charged you AED 15.00 today"))
        assertNotNull(r)
        assertEquals("sig.high", r!!.matchedRuleName)
    }

    @Test
    fun `collision - priority tie broken by longest matched signature`() {
        val generic = sigRule("sig.generic", listOf("SUPERPAY"), priority = 50)
        val specific = sigRule("sig.specific", listOf("SUPERPAY CARD"), priority = 50)
        // Registered generic-first so registration order alone would pick the
        // wrong rule; the longer (more specific) signature must win.
        val parser = TransactionParser(listOf(generic, specific), "USD")

        val r = parser.parse(msg("SUPERPAY CARD purchase of AED 30.00 confirmed"))
        assertNotNull(r)
        assertEquals("sig.specific", r!!.matchedRuleName)
    }

    @Test
    fun `collision - full tie falls back to first-registered and logs a warning`() {
        val first = sigRule("sig.first", listOf("CARDX"), priority = 50)
        val second = sigRule("sig.second", listOf("CARDY"), priority = 50)
        val warnings = mutableListOf<String>()
        val parser = TransactionParser(listOf(first, second), "USD", onWarning = warnings::add)

        val r = parser.parse(msg("CARDX and CARDY both flagged a payment of AED 9.99"))
        assertNotNull(r)
        assertEquals("sig.first", r!!.matchedRuleName)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("sig.first"))
        assertTrue(warnings[0].contains("sig.second"))
    }

    // --- 4. Extraction failure: signature must not consume the message ---

    @Test
    fun `signature without plausible amount falls through to classic regex rules`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")

        // Contains the Binance Mastercard signature but no currency-adjacent
        // amount; the bare number 99.90 is only parseable by the classic
        // fallback regex rule, proving the signature rule did not consume it.
        val r = parser.parse(msg("Binance Mastercard notice, you were debited 99.90 for subscription renewal"))
        assertNotNull(r)
        assertEquals("builtin.en.debit.bare", r!!.matchedRuleName)
        assertEquals(BigDecimal("99.90"), r.amount)
        assertEquals("USD", r.currencyCode) // engine fallback, not fabricated
    }

    @Test
    fun `signature with no amount anywhere yields no transaction at all`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")
        // Digits present (so the ingest fast-path would not drop it) but no
        // amount any rule can parse: existing unparsed behavior applies.
        assertNull(parser.parse(msg("Binance Mastercard: service maintenance notice 2026")))
    }

    @Test
    fun `merchant extraction failure is non-fatal`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")
        // No "at/to" clause and no clean vendor header: merchant is null but
        // the transaction still parses.
        val r = parser.parse(msg("AED 75.50 charged. Binance Mastercard 1234 statement ready."))
        assertNotNull(r)
        assertEquals("builtin.sig.binance.card", r!!.matchedRuleName)
        assertEquals(BigDecimal("75.50"), r.amount)
        assertNull(r.merchant)
    }

    // --- Sender filter still applies to signature rules ---

    @Test
    fun `signature rule with sender pattern ignores other senders`() {
        val rule = sigRule(
            "sig.sender.bound",
            listOf("MEGABANK CARD"),
            senderPattern = "megabank",
        )
        val parser = TransactionParser(listOf(rule), "USD")

        val matched = parser.parse(msg("MEGABANK CARD spent AED 12.00", sender = "com.megabank.app"))
        assertNotNull(matched)
        assertEquals("sig.sender.bound", matched!!.matchedRuleName)

        assertNull(parser.parse(msg("MEGABANK CARD spent AED 12.00", sender = "com.other.app")))
    }

    // --- 5. Regression guard: the classic regex suite lives unmodified in ---
    // --- TransactionParserTest; this only spot-checks coexistence.        ---

    @Test
    fun `classic regex rules are unaffected when no signature matches`() {
        val parser = TransactionParser(BuiltInRules.all, "USD")
        val r = parser.parse(msg("You spent AED 250.75 at CARREFOUR DUBAI. Avl bal AED 1,200"))
        assertNotNull(r)
        assertEquals(BigDecimal("250.75"), r!!.amount)
        assertEquals("CARREFOUR DUBAI", r.merchant)
        assertEquals(TransactionDirection.DEBIT, r.direction)
    }
}
