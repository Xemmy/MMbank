package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind

/**
 * Decides what a captured transaction *is* ([TransactionKind]) from what the
 * parser already knows about it. Pure, so the whole decision table is unit
 * tested rather than spread across the ingest pipeline.
 *
 * Precedence, strongest first:
 *  1. the matched rule's explicit kind ([Input.ruleKind]) -- the user wrote
 *     that rule for exactly this message;
 *  2. a kind the user taught for this merchant ([Input.learnedKind]);
 *  3. built-in rule families whose meaning is fixed (Tabby adjustments,
 *     the card-payment credit leg);
 *  4. the cashback tag;
 *  5. message wording (card payment, own-account transfer, ATM, refund);
 *  6. a credit landing on a credit card -- money onto a card is a payment
 *     or a refund, never income;
 *  7. the plain default for the direction.
 *
 * Whatever wins is finally checked against the direction ([coerce]): a
 * spend can only be a debit and income/refund/cashback only a credit, so a
 * stale learned kind can never flip the sign of a total.
 */
object TransactionKindDetector {

    data class Input(
        val direction: TransactionDirection,
        val matchedRuleName: String? = null,
        val rawMessage: String? = null,
        val merchant: String? = null,
        val accountType: AccountType? = null,
        val category: TransactionCategory = TransactionCategory.NORMAL,
        val ruleKind: TransactionKind? = null,
        val learnedKind: TransactionKind? = null,
    )

    const val ADJUST_DECREASE_RULE = "builtin.en.adjust.decrease"
    const val ADJUST_INCREASE_RULE = "builtin.en.adjust.increase"
    const val CARD_PAYMENT_RULE_PREFIX = "builtin.en.transfer."

    /** Merchant text [com.xemmy.mmbank.ui.screens.accounts] writes on balance corrections. */
    const val BALANCE_ADJUSTMENT_MERCHANT = "Balance adjustment"

    private val CARD_PAYMENT_DEBIT = Regex(
        "\\b(?:credit\\s*card|cc)\\s+(?:bill\\s+)?payment\\b|\\bcard\\s+(?:bill\\s+)?payment\\b|" +
            "\\bpayment\\s+(?:to|towards|for)\\s+(?:your\\s+)?(?:credit\\s+)?card\\b|" +
            "سداد\\s+(?:فاتورة\\s+)?البطاقة",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_PAYMENT_CREDIT = Regex(
        "\\bpayment\\s+(?:of\\s+\\S+\\s+)?(?:has\\s+been\\s+|was\\s+)?received\\b|" +
            "\\bthank\\s+you\\s+for\\s+(?:your\\s+)?payment\\b|" +
            "\\bpayment\\b.{0,60}\\bcard\\s+ending\\b.{0,40}\\bcredited\\b",
        RegexOption.IGNORE_CASE,
    )
    private val OWN_TRANSFER = Regex(
        "\\b(?:own|between\\s+(?:your|my))\\s+accounts?\\b|\\binternal\\s+transfer\\b|" +
            "\\btransfer(?:red)?\\s+(?:to|from)\\s+(?:your|my)\\s+(?:own\\s+)?(?:savings|account)\\b|" +
            "بين\\s+حساباتك",
        RegexOption.IGNORE_CASE,
    )
    private val ATM = Regex(
        "\\batm\\b|\\bcash\\s+withdrawa?l\\b|\\bcash\\s+withdrawn\\b|سحب\\s+نقدي",
        RegexOption.IGNORE_CASE,
    )
    private val REFUND = Regex(
        "\\brefund(?:ed)?\\b|\\breversal\\b|\\breversed\\b|\\bcharge\\s*back\\b|\\bpurchase\\s+return\\b|" +
            "استرداد|استرجاع",
        RegexOption.IGNORE_CASE,
    )

    fun detect(input: Input): TransactionKind {
        val text = listOfNotNull(input.rawMessage, input.merchant).joinToString(" ")
        val credit = input.direction == TransactionDirection.CREDIT
        val rule = input.matchedRuleName.orEmpty()

        val raw = input.ruleKind
            ?: input.learnedKind
            ?: when {
                rule == ADJUST_DECREASE_RULE -> TransactionKind.REFUND
                rule == ADJUST_INCREASE_RULE -> TransactionKind.SPEND
                rule.startsWith(CARD_PAYMENT_RULE_PREFIX) -> TransactionKind.CARD_PAYMENT
                input.merchant == BALANCE_ADJUSTMENT_MERCHANT -> TransactionKind.ADJUSTMENT
                input.category == TransactionCategory.CASHBACK_EARNED -> TransactionKind.CASHBACK
                !credit && CARD_PAYMENT_DEBIT.containsMatchIn(text) -> TransactionKind.CARD_PAYMENT
                credit && CARD_PAYMENT_CREDIT.containsMatchIn(text) &&
                    (input.accountType == AccountType.CREDIT_CARD || text.contains("card", ignoreCase = true)) ->
                    TransactionKind.CARD_PAYMENT
                OWN_TRANSFER.containsMatchIn(text) -> TransactionKind.TRANSFER
                !credit && ATM.containsMatchIn(text) -> TransactionKind.ATM
                credit && REFUND.containsMatchIn(text) -> TransactionKind.REFUND
                credit && input.accountType == AccountType.CREDIT_CARD -> TransactionKind.CARD_PAYMENT
                else -> null
            }
            ?: TransactionKind.defaultFor(input.direction)
        return coerce(raw, input.direction)
    }

    /**
     * Makes [kind] agree with [direction]. Transfers, card payments and
     * adjustments are legitimately either sign; the rest are one-sided, and a
     * mismatch falls back to the plain kind for the direction.
     */
    fun coerce(kind: TransactionKind, direction: TransactionDirection): TransactionKind {
        val credit = direction == TransactionDirection.CREDIT
        return when (kind) {
            TransactionKind.SPEND, TransactionKind.ATM -> if (credit) TransactionKind.defaultFor(direction) else kind
            TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.CASHBACK ->
                if (credit) kind else TransactionKind.defaultFor(direction)
            TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT, TransactionKind.ADJUSTMENT -> kind
        }
    }

    /**
     * The one-time post-upgrade pass (see AppContainer.reclassifyLedgerOnce):
     * the better kind for a row the SQL migration could only give a plain
     * SPEND/INCOME to -- an ATM withdrawal or refund recognizable from its
     * message text -- or null to leave it alone. Never touches a kind the
     * user set, a linked transfer leg, or anything the migration already
     * classified beyond the plain default.
     */
    fun reclassify(
        tx: com.xemmy.mmbank.domain.model.Transaction,
        accountType: AccountType?,
    ): TransactionKind? {
        if (tx.kindUserSet || tx.linkedTransactionId != null) return null
        if (tx.kind != TransactionKind.defaultFor(tx.direction)) return null
        val detected = detect(
            Input(
                direction = tx.direction,
                matchedRuleName = tx.matchedRuleName,
                rawMessage = tx.rawMessage,
                merchant = tx.merchant,
                accountType = accountType,
                category = tx.category,
            ),
        )
        return detected.takeIf { it != tx.kind }
    }

    /**
     * Kind for a row written before kinds existed (an upgrade, or a backup
     * from an older build), plus whether its `excludeFromStats` flag should
     * survive. Mirrors [com.xemmy.mmbank.data.local.AppDatabase]'s
     * MIGRATION_25_26 backfill: the old automatic exclusions (adjustments,
     * reconciled transfers, card-payment credits) become kinds and drop the
     * flag; anything else excluded was the user's own choice and stays.
     */
    fun fromLegacy(
        direction: TransactionDirection,
        matchedRuleName: String?,
        merchant: String?,
        category: TransactionCategory,
        excludeFromStats: Boolean,
        linked: Boolean,
        onCreditCard: Boolean,
    ): Pair<TransactionKind, Boolean> {
        val rule = matchedRuleName.orEmpty()
        val kind = when {
            rule == ADJUST_DECREASE_RULE -> TransactionKind.REFUND
            rule == ADJUST_INCREASE_RULE -> TransactionKind.SPEND
            merchant == BALANCE_ADJUSTMENT_MERCHANT -> TransactionKind.ADJUSTMENT
            linked || rule.startsWith(CARD_PAYMENT_RULE_PREFIX) ->
                if (onCreditCard || rule.startsWith(CARD_PAYMENT_RULE_PREFIX)) TransactionKind.CARD_PAYMENT
                else TransactionKind.TRANSFER
            category == TransactionCategory.CASHBACK_EARNED -> TransactionKind.CASHBACK
            else -> null
        }
        val automaticExclusion = kind != null && kind != TransactionKind.CASHBACK
        return coerce(kind ?: TransactionKind.defaultFor(direction), direction) to
            (excludeFromStats && !automaticExclusion)
    }
}
