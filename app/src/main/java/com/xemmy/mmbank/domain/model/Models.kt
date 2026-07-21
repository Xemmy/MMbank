package com.xemmy.mmbank.domain.model

import java.math.BigDecimal

/** Where a captured message came from. */
enum class TransactionSource { SMS, PUSH_NOTIFICATION, MANUAL, BINANCE_SYNC }

enum class TransactionDirection { DEBIT, CREDIT }

/** Semantic tag on top of [TransactionDirection], for rows that aren't an ordinary spend/receive. */
enum class TransactionCategory { NORMAL, CASHBACK_EARNED }

/**
 * User-facing spending category, assignable per transaction (like account and
 * person) and auto-suggested from the merchant at capture time. A fixed set —
 * kept deliberately small so the Dashboard breakdown stays legible and no
 * category-management screen is needed.
 */
enum class SpendingCategory(val label: String, val emoji: String) {
    UNCATEGORIZED("Uncategorized", "🏷️"),
    GROCERIES("Groceries", "🛒"),
    DINING("Dining", "🍽️"),
    TRANSPORT("Transport", "🚕"),
    FUEL("Fuel", "⛽"),
    SHOPPING("Shopping", "🛍️"),
    UTILITIES("Utilities", "💡"),
    ENTERTAINMENT("Entertainment", "🎬"),
    HEALTH("Health", "🩺"),
    TRAVEL("Travel", "✈️"),
    TRANSFERS("Transfers", "🔁"),
    INCOME("Income", "💰"),
    FEES("Fees", "🏦"),
    OTHER("Other", "•"),
}

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
    val category: TransactionCategory = TransactionCategory.NORMAL,
    /** Account the matched rule pins its transactions to; wins over detection. */
    val assignAccountId: Long? = null,
    /** Persisted onto the transaction: keep it in the ledger but out of analytics. */
    val excludeFromStats: Boolean = false,
    /**
     * Pins the spending category instead of leaving it to
     * [com.xemmy.mmbank.domain.parser.MerchantCategorizer]'s merchant-text
     * guess — set by rules that already know what the message is (e.g. a
     * recognized credit-card statement payment is [SpendingCategory.TRANSFERS]
     * regardless of what "merchant" text happens to be nearby).
     */
    val spendingCategoryOverride: SpendingCategory? = null,
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
    // --- Bank details (populated manually or via the bulk-paste importer) ---
    val bankName: String? = null,
    val accountTitle: String? = null,
    val iban: String? = null,
    val swift: String? = null,
    // --- Loan / financing details (relevant for LOAN and CREDIT_CARD types) ---
    val outstandingBalance: BigDecimal? = null,
    val nextInstallmentAmount: BigDecimal? = null,
    /** Day of month (1-31) the installment is due. */
    val dueDayOfMonth: Int? = null,
    /** Annual profit / interest rate as a percentage, e.g. 5.49. */
    val profitRatePa: Double? = null,
    val maturityDateMillis: Long? = null,
    /** Free text: add-ons, early-settlement terms, penalties, etc. */
    val loanNotes: String? = null,
    /**
     * Day of month (1-31) the card's statement cycle starts, e.g. 10 = the
     * "10th of last month → 9th of this month" window. Null = no statement
     * cycle; date presets fall back to a rolling window.
     */
    val statementCycleDay: Int? = null,
    /**
     * True for sample rows created by the Settings "Demo mode" toggle. Real
     * accounts are always false; demo mode hides real rows and shows only
     * these, then deletes them when switched off.
     */
    val isDemo: Boolean = false,
) {
    /**
     * Rough early-settlement guidance. Most UAE lenders cap the early-settlement
     * fee at 1% of the outstanding balance (or 3 months' profit). If the profit
     * you would still pay over the remaining term clearly exceeds that fee,
     * settling early tends to pay off.
     */
    val earlySettlementFeeEstimate: BigDecimal?
        get() = outstandingBalance?.let { it.multiply(BigDecimal("0.01")) }
}

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
    val category: TransactionCategory = TransactionCategory.NORMAL,
    /** Person this transaction belongs to (user-assigned; defaults from Settings). */
    val person: String? = null,
    /** Spending category (auto-suggested from the merchant, user-overridable). */
    val spendingCategory: SpendingCategory = SpendingCategory.UNCATEGORIZED,
    /**
     * Stays visible in the raw ledger (and keeps counting toward account
     * balances) but is omitted from analytics: balance corrections, Tabby
     * refund adjustments, internal transfers, cash refunds.
     */
    val excludeFromStats: Boolean = false,
    /**
     * The counterpart transaction this one was reconciled with — the other
     * leg of a credit-card statement payment (see
     * [com.xemmy.mmbank.domain.usecase.TransferMatcher]). Null until/unless
     * a matching opposite-direction transaction is found; set on both rows
     * at once, so it's always symmetric.
     */
    val linkedTransactionId: Long? = null,
    /** True for sample rows created by the Settings "Demo mode" toggle. */
    val isDemo: Boolean = false,
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
    /**
     * Account every transaction matched by this rule is assigned to (the
     * "default assign", editable in the rule editor). Overrides card-number
     * detection; null keeps the automatic behavior.
     */
    val assignAccountId: Long? = null,
    /** True for sample rules created by the Settings "Demo mode" toggle. */
    val isDemo: Boolean = false,
)

/** User-defined friendly name for a transaction sender (SMS short code or app package). */
data class SenderLabel(
    val sender: String,
    val label: String,
)

/**
 * Bank account details extracted from pasted text by [com.xemmy.mmbank.domain.parser.BankDetailsParser].
 * Every field is optional; the importer fills in whatever it can recognise.
 */
data class ParsedBankDetails(
    val bankName: String? = null,
    val accountTitle: String? = null,
    val accountNumber: String? = null,
    val iban: String? = null,
    val swift: String? = null,
    val currencyCode: String? = null,
) {
    val hasAnything: Boolean
        get() = listOfNotNull(bankName, accountTitle, accountNumber, iban, swift, currencyCode).isNotEmpty()
}

/**
 * Total value of one Binance wallet (Spot, Funding, Earn, Trading Bots, …),
 * as reported BTC-valued by Binance and converted to USD at sync time.
 */
data class WalletBalance(
    val walletName: String,
    val balanceBtc: BigDecimal,
    val valueUsd: BigDecimal?,
    val updatedAt: Long,
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
