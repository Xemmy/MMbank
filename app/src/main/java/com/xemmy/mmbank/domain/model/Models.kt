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
    /**
     * Hides this account's card from the Dashboard's [com.xemmy.mmbank.ui.components.AccountCarousel]
     * (set from [com.xemmy.mmbank.ui.components.ManageAccountsBottomSheet]). Independent
     * of [archived]: a hidden account still counts toward net worth and can be
     * unhidden any time, where an archived one is retired from the ledger entirely.
     */
    val hiddenFromDashboard: Boolean = false,
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
    /**
     * Dynamic [Category] assigned to this transaction (see [CategoryRepository]
     * / [com.xemmy.mmbank.domain.parser.CategoryMatcher]), independent of the
     * fixed [spendingCategory] enum. Null until a merchant mapping matches or
     * the user/AI-suggestion flow assigns one.
     */
    val categoryId: Long? = null,
    /**
     * True while an AI-suggested category (the suggested* fields below) is
     * awaiting human approval in the Uncategorized queue -- never auto-applied,
     * same invariant as [com.xemmy.mmbank.domain.ai.AiSelfHealingRuleGenerator]'s
     * pending rules.
     */
    val needsCategoryApproval: Boolean = false,
    val suggestedCategoryName: String? = null,
    val suggestedCategoryIcon: String? = null,
    val suggestedCategoryColorHex: String? = null,
    /**
     * Raw merchant text as pulled from [rawMessage] at capture time, before any
     * later editing of [merchant] -- lets the Review screen show what the
     * parser/AI actually saw, independent of a subsequent manual correction.
     */
    val extractedMerchant: String? = null,
    /**
     * 0-100 confidence in this transaction's automated categorization, scored
     * at capture time by [com.xemmy.mmbank.domain.parser.AiConfidenceScorer].
     * Null for rows recorded before this field existed or entered manually.
     */
    val aiConfidence: Int? = null,
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

/** Detected repeat-charge rhythm — see [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]. */
enum class SubscriptionCadence(val nominalDays: Long) { WEEKLY(7L), MONTHLY(30L) }

/**
 * One detected or manually confirmed salary/income event — see
 * [com.xemmy.mmbank.domain.intelligence.CashFlowForecaster]. Not persisted;
 * derived on demand from the transaction ledger.
 */
data class Paycheck(
    val amount: BigDecimal,
    val currencyCode: String,
    val timestampMillis: Long,
    val merchant: String? = null,
)

/**
 * Manual payday override (Settings): the day of the month salary is expected
 * to land on, wins over auto-detection when set. `dayOfMonth` = 0 means no
 * override — [com.xemmy.mmbank.domain.intelligence.CashFlowForecaster] falls
 * back to the pattern it detects from recurring credits.
 */
data class PaycheckSchedule(val dayOfMonth: Int = 0)

/**
 * A named savings/debt-payoff target with progress tracked in [currentAmount]
 * — see the Goals screen (ui/screens/goals/GoalsScreen.kt). Progress can be
 * built up manually or via "Auto-Allocate Surplus" (month-end surplus applied
 * to a chosen goal).
 */
data class SavingsGoal(
    val id: Long = 0,
    val title: String,
    val targetAmount: BigDecimal,
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val targetDateMillis: Long? = null,
    val icon: String = "🎯",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
) {
    val progressFraction: Double
        get() = if (targetAmount.signum() <= 0) {
            0.0
        } else {
            (currentAmount.toDouble() / targetAmount.toDouble()).coerceIn(0.0, 1.0)
        }
}

/**
 * Snapshot of the Dashboard's Financial Health Summary Card — see
 * [com.xemmy.mmbank.domain.intelligence.FinancialHealthEngine].
 */
data class FinancialHealthMetrics(
    /** ((Income - Expenses) / Income) * 100; 0 when there's no income to divide by. */
    val savingsRatePercent: Double,
    /** LiquidBalance / AverageMonthlyFixedExpenses; null when there are no fixed expenses to divide by. */
    val cashRunwayMonths: Double?,
    /** Income - Expenses for the period. */
    val netCashFlow: Double,
    val income: Double,
    val expenses: Double,
)

/**
 * A recurring charge detected by [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]
 * from historical transactions grouped by merchant, persisted so the Subscriptions
 * Radar & Renewal Calendar screen has something to show without re-scanning on
 * every launch.
 */
data class Subscription(
    val id: Long = 0,
    val merchant: String,
    val averageAmount: BigDecimal,
    val currencyCode: String,
    val cadence: SubscriptionCadence,
    /** Millis of the next expected charge, projected from the last seen occurrence. */
    val nextDueDateMillis: Long,
    /** Millis of the most recent occurrence this detection was based on. */
    val lastSeenMillis: Long,
    /** How many qualifying charges were found — higher means a more confident detection. */
    val occurrenceCount: Int,
    /** User dismissed this as a false positive; kept around so it isn't re-detected as new. */
    val dismissed: Boolean = false,
)
