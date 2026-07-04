package com.xemmy.mmbank.domain.model

import java.math.BigDecimal

/** Where a captured message came from. */
enum class TransactionSource { SMS, PUSH_NOTIFICATION, MANUAL, BINANCE_SYNC }

enum class TransactionDirection { DEBIT, CREDIT }

/**
 * Ledger types managed by the app. Balances are computed uniformly
 * (opening + credits - debits); liability accounts (CREDIT_CARD, LOAN)
 * simply trend negative as spending is captured.
 */
enum class AccountType { CHECKING, SAVINGS, CASH, CREDIT_CARD, LOAN, CRYPTO_SPOT, INVESTMENT }

/** Raw payload captured from either the notification listener or the SMS receiver. */
data class CapturedMessage(
    val source: TransactionSource,
    /** SMS originating address, or the posting app's package name for push notifications. */
    val sender: String?,
    val title: String?,
    val body: String,
    val timestampMillis: Long,
)

/** Output of the regex parsing engine, before dedup/persistence. */
data class ParsedTransaction(
    val amount: BigDecimal,
    val currencyCode: String,
    val direction: TransactionDirection,
    val merchant: String?,
    val matchedRuleName: String,
    val accountNumberSuffix: String? = null,
)

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    val currencyCode: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val archived: Boolean = false,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * The identifying digits of the real bank account or card (e.g. "617", "810001", "963").
     * Captured transactions whose message reveals a matching number are auto-assigned here.
     */
    val accountNumber: String? = null,
)

data class Transaction(
    val id: Long = 0,
    val accountId: Long? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val direction: TransactionDirection,
    val merchant: String? = null,
    val source: TransactionSource,
    val sender: String? = null,
    val rawMessage: String? = null,
    val matchedRuleName: String? = null,
    /** Event time (SMS timestamp / notification post time), used by the dedup window. */
    val timestampMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** Account number suffix extracted from the message (last 3-4 digits like *617 or XXX810001). */
    val accountNumberSuffix: String? = null,
)

/**
 * A user-defined parsing rule, editable in-app without recompiling the APK.
 * [pattern] must contain a named group `(?<amount>...)`; optional named groups:
 * `(?<currency>...)` and `(?<merchant>...)`.
 */
data class ParsingRule(
    val id: Long = 0,
    val name: String,
    val pattern: String,
    /** Optional regex matched against the SMS sender / notification package name. */
    val senderPattern: String? = null,
    val direction: TransactionDirection,
    /** Used when the pattern has no currency group or it doesn't match. */
    val defaultCurrency: String? = null,
    /** Higher priority rules are tried first. Custom rules default to 100, built-ins run at 0. */
    val priority: Int = 100,
    val enabled: Boolean = true,
    /** Last sample text used in the rule tester, persisted for convenience. */
    val sampleText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A spot balance pulled from Binance via the read-only API key. */
data class AssetBalance(
    val asset: String,
    val free: BigDecimal,
    val locked: BigDecimal,
    val priceUsd: BigDecimal?,
    val valueUsd: BigDecimal?,
    val updatedAt: Long,
) {
    val total: BigDecimal get() = free + locked
}

data class ExchangeRate(
    val baseCode: String,
    val quoteCode: String,
    val rate: BigDecimal,
    val fetchedAt: Long,
)

/** Result of pushing a captured message through parse + dedup + insert. */
sealed interface IngestResult {
    data class Inserted(val transactionId: Long) : IngestResult
    data class Duplicate(val existingTransactionId: Long) : IngestResult
    data object NoMatch : IngestResult
}
