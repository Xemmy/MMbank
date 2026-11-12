package com.xemmy.mmbank.domain.model

import java.math.BigDecimal
import java.time.Period

/**
 * Where a captured message came from. [PASTED] is text the user pasted into
 * the importer themselves — parsed exactly like a live capture, but kept
 * distinguishable in the ledger since its timestamp comes from the message's
 * own printed date rather than from the moment it arrived.
 */
enum class TransactionSource { SMS, PUSH_NOTIFICATION, MANUAL, BINANCE_SYNC, PASTED }

enum class TransactionDirection { DEBIT, CREDIT }

/** Semantic tag on top of [TransactionDirection], for rows that aren't an ordinary spend/receive. */
enum class TransactionCategory { NORMAL, CASHBACK_EARNED }

/**
 * What a bank-reported figure at the end of a message means (see
 * [com.xemmy.mmbank.domain.parser.BalanceExtractor]): a balance held, or
 * credit still available (a card limit remaining). Which one a message carries
 * depends on its wording; what that implies for an account depends on the
 * account's type, so the integrity check decides.
 */
enum class ReportedBalanceKind { ACCOUNT_BALANCE, AVAILABLE_CREDIT }

/**
 * What a transaction *is*, as opposed to which way the money moved
 * ([TransactionDirection]). Every total in the app -- Spent, Received,
 * budgets, reports, forecasts -- is derived from this through
 * [com.xemmy.mmbank.domain.ledger.LedgerMath], so a card payment or a move
 * between two of the user's own accounts can never be counted as spending on
 * one side and income on the other.
 *
 * Every kind still moves its account's balance; the kind only decides how the
 * row reads in analytics:
 *  - [SPEND], [ATM]: spending.
 *  - [REFUND]: money back for an earlier purchase -- lowers spending rather
 *    than counting as income.
 *  - [INCOME]: money received.
 *  - [CASHBACK]: rewards; shown on the Cashback screen, in neither total.
 *  - [TRANSFER], [CARD_PAYMENT]: money moving between the user's own
 *    accounts (a card payment is the special case of paying a credit card
 *    off); in neither total, because the purchases on the card were already
 *    counted when they happened.
 *  - [ADJUSTMENT]: balance corrections; in neither total.
 */
enum class TransactionKind(val label: String) {
    SPEND("Spent"),
    INCOME("Received"),
    REFUND("Refund"),
    ATM("Cash withdrawal"),
    TRANSFER("Transfer"),
    CARD_PAYMENT("Card payment"),
    CASHBACK("Cashback"),
    ADJUSTMENT("Adjustment"),
    ;

    /** True for kinds that move money between the user's own accounts. */
    val isInternalMove: Boolean get() = this == TRANSFER || this == CARD_PAYMENT

    /**
     * The only direction this kind can have, or null for kinds that go either
     * way (each leg of a transfer or card payment, a correction up or down).
     */
    val fixedDirection: TransactionDirection?
        get() = when (this) {
            SPEND, ATM -> TransactionDirection.DEBIT
            INCOME, REFUND, CASHBACK -> TransactionDirection.CREDIT
            TRANSFER, CARD_PAYMENT, ADJUSTMENT -> null
        }

    companion object {
        /** The kind a plain, unclassified transaction in [direction] is. */
        fun defaultFor(direction: TransactionDirection): TransactionKind =
            if (direction == TransactionDirection.CREDIT) INCOME else SPEND

        /** Lenient parse for persisted/backed-up names; unknown -> [defaultFor]. */
        fun parse(name: String?, direction: TransactionDirection): TransactionKind =
            name?.let { n -> entries.firstOrNull { it.name == n } } ?: defaultFor(direction)
    }
}

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
    /**
     * The kind the matched rule pins (see [ParsingRule.assignKind]); null
     * leaves it to [com.xemmy.mmbank.domain.ledger.TransactionKindDetector].
     */
    val kindOverride: TransactionKind? = null,
    /** The balance the bank printed in the same message, if any; null when it printed none. */
    val reportedBalance: BigDecimal? = null,
    val reportedBalanceKind: ReportedBalanceKind? = null,
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
    /** A credit card's limit, for available credit and utilisation; null = not set. */
    val creditLimit: BigDecimal? = null,
    /**
     * Extra card numbers that belong to this account, comma-separated -- a
     * debit card on a current account, supplementary cards on a credit card.
     * Matched against captured messages alongside [accountNumber] (see
     * [com.xemmy.mmbank.domain.account.AccountNumberMatcher]).
     */
    val cardEndings: String? = null,
    /** Optional user-picked ARGB colour tinting this account's card and row accents; null = theme default. */
    val accentColor: Int? = null,
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
     * The user's manual "hide from analytics" switch: the row stays in the
     * ledger (and keeps counting toward account balances) but is omitted
     * from every total regardless of [kind]. Transfers, card payments and
     * balance corrections no longer need it -- their [kind] already keeps
     * them out of Spent/Received.
     */
    val excludeFromStats: Boolean = false,
    /**
     * The counterpart transaction this one was reconciled with — the other
     * leg of a transfer or credit-card payment (see
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
    /**
     * What this transaction is -- spend, income, refund, transfer, card
     * payment... -- see [TransactionKind]. Set at capture time by
     * [com.xemmy.mmbank.domain.ledger.TransactionKindDetector]; defaults to
     * the plain kind for [direction].
     */
    val kind: TransactionKind = TransactionKind.defaultFor(direction),
    /**
     * True once the user picked [kind] by hand. Automatic re-classification
     * (backfills, transfer linking/unlinking, learned merchant kinds) never
     * overrides a user-set kind.
     */
    val kindUserSet: Boolean = false,
    /**
     * The balance the bank itself printed in the message this row came from
     * ("available balance AED 46,120.85"), in this row's currency; null when
     * the message carried none (manual rows, most notifications). The bank's
     * statement of what the account held right after this transaction -- the
     * reference the integrity check reconciles the app's own balance against.
     */
    val reportedBalance: BigDecimal? = null,
    val reportedBalanceKind: ReportedBalanceKind? = null,
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
    /**
     * Kind every transaction matched by this rule gets (e.g. a rule for the
     * bank's "card payment" message -> [TransactionKind.CARD_PAYMENT]); null
     * keeps the automatic classification.
     */
    val assignKind: TransactionKind? = null,
)

/**
 * A learned merchant -> kind rule, written when the user re-classifies a
 * transaction with "remember for this merchant" (see
 * [com.xemmy.mmbank.domain.ledger.MerchantKindMatcher]). [merchantPattern]
 * holds the raw merchant; the normalized key is recomputed at match time.
 */
data class MerchantKindMapping(
    val id: Long = 0,
    val merchantPattern: String,
    val kind: TransactionKind,
    val createdAt: Long = System.currentTimeMillis(),
)

/** User-defined friendly name for a transaction sender (SMS short code or app package). */
data class SenderLabel(
    val sender: String,
    val label: String,
)

/**
 * A kind of message the user has told the app to stop capturing — a promo, an
 * OTP, a marketing blast.
 *
 * [sample] is the text they pasted, kept verbatim so the list stays readable
 * and removable. [fingerprint] is that sample's template shape per
 * [com.xemmy.mmbank.domain.parser.MessageSimilarity.fingerprint], stored
 * alongside it so the capture path can reject the common case (the same
 * template again, only the numbers changed) on an exact string comparison,
 * before falling back to the fuzzier token overlap.
 */
data class IgnoredMessagePattern(
    val id: Long = 0,
    val fingerprint: String,
    val sample: String,
    val createdAt: Long,
)

/**
 * A word or phrase the user muted: any captured message (title or body)
 * containing [keyword], case-insensitively, is skipped. The coarser sibling of
 * [IgnoredMessagePattern], for "anything mentioning X" rather than "this
 * template". [keyword] is stored trimmed and lowercased.
 */
data class IgnoredKeyword(
    val id: Long = 0,
    val keyword: String,
    val createdAt: Long,
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

    /**
     * The message matched a template the user muted — see
     * [IgnoredMessagePattern]. Distinct from [NoMatch] (which means "nothing
     * here looked like a transaction") because this one was a deliberate
     * choice, and the paste importer reports the two counts separately.
     */
    data object Ignored : IngestResult
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
    /**
     * What this subscription used to cost, when the charge amount changed
     * partway through the series; null when the price has been steady.
     *
     * [averageAmount] is then the *current* price, taken from the charges since
     * the change, so "↑ was 45" can be shown against today's figure.
     */
    val previousAmount: BigDecimal? = null,
)

/**
 * Recurrence rhythm for a manually tracked [Bill]; kept nullable on the bill
 * itself for one-off entries.
 *
 * Expressed as a calendar [Period] rather than a nominal day count. A bill is
 * due on a *date* the user picked ("rent on the 1st"), so advancing it has to
 * move by calendar months/years -- adding a flat 30 or 365 days walks the due
 * date backwards through the month and ignores leap years.
 * Contrast [SubscriptionCadence], which stays in nominal days because it
 * describes an observed rhythm the detector measures in days, not a date rule.
 */
enum class BillRecurrence(val period: Period) {
    WEEKLY(Period.ofWeeks(1)),
    MONTHLY(Period.ofMonths(1)),
    YEARLY(Period.ofYears(1)),
}

/**
 * A manually tracked bill (rent, EMI, a variable-amount utility, a one-off
 * payment). Unlike [Subscription], never auto-detected from transaction
 * history: these often don't repeat with the stable merchant name and <5%
 * amount variance [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]
 * requires, or have no transaction history at all yet.
 */
data class Bill(
    val id: Long = 0,
    val name: String,
    val amount: BigDecimal,
    val currencyCode: String,
    /** Null means one-off -- marking it paid removes it rather than advancing the due date. */
    val recurrence: BillRecurrence? = null,
    val nextDueDateMillis: Long,
    val createdAt: Long,
    /** Day of month the user picked, kept so month-end clamping in a short month is not permanent. */
    val dueDayAnchor: Int? = null,
)

/**
 * A person shared expenses are split with -- a lightweight name, not a
 * bank-linked contact. See [SplitGroup] / [GroupExpense].
 */
data class Friend(
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
)

/** How a [GroupExpense]'s amount is divided among its participants; see [com.xemmy.mmbank.domain.split.SplitAllocator]. */
enum class SplitType { EQUAL, EXACT, PERCENTAGE, ADJUSTMENT, SETTLEMENT }

/**
 * A shared-expense group (e.g. "Roommates", "Trip to Muscat"). Membership is
 * a set of [Friend] ids; the app's own user is always an implicit member,
 * represented as a null id everywhere a participant is identified (see
 * [GroupExpense.paidByFriendId], [ExpenseSplit.friendId]) rather than a row
 * of its own -- there's no "me" contact to create or delete.
 */
data class SplitGroup(
    val id: Long = 0,
    val name: String,
    val icon: String = "👥",
    val memberFriendIds: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
)

/**
 * One shared expense within a [SplitGroup]: paid by one participant (null =
 * "me"), divided among its [ExpenseSplit] rows. [SplitType.SETTLEMENT] marks
 * a manual "settle up" payment recorded the same way as any other expense --
 * paid in full by the person settling, with the entire amount as a single
 * split owed to the person they're paying -- so it nets out through the same
 * balance math ([com.xemmy.mmbank.domain.split.GroupBalances]) without a
 * separate code path.
 */
data class GroupExpense(
    val id: Long = 0,
    val groupId: Long,
    val description: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val paidByFriendId: Long? = null,
    val splitType: SplitType,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One participant's (null = "me") share of a [GroupExpense]. */
data class ExpenseSplit(
    val id: Long = 0,
    val expenseId: Long,
    val friendId: Long? = null,
    val owedAmount: BigDecimal,
)
