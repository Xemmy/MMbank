package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection

/**
 * Built-in multi-language rules covering common English and Arabic bank
 * message layouts. They run at low priority so user-defined rules (default
 * priority 100) always win. All patterns match against [TextNormalizer]
 * output, so digits are already ASCII regardless of the source script.
 *
 * Every rule ends with [ACCTAIL], an optional trailing scan that pulls the
 * account/card number out of the message (e.g. "*617" or "حساب رقم XXX810001")
 * without affecting whether the rule matches. The extracted number is later
 * matched against each account's configured number to auto-assign the
 * transaction to the right account/card.
 */
object BuiltInRules {

    // Restricted to known codes/symbols so stray 3-letter words are never
    // mistaken for a currency under IGNORE_CASE. The body is separate from the
    // named group so a pattern can also match a *second* currency token
    // (e.g. a trailing cashback figure) without a duplicate group name.
    private const val CUR_BODY =
        "USD|USDT|EUR|GBP|SAR|AED|EGP|KWD|QAR|OMR|BHD|JOD|LBP|IQD|TRY|INR|PKR|JPY|CNY|CHF|CAD|AUD" +
            "|[$€£₹₺¥]|ر\\.\\s?س|د\\.\\s?إ|ج\\.\\s?م|د\\.\\s?ك|ر\\.\\s?ق|ر\\.\\s?ع|د\\.\\s?ب|د\\.\\s?ا|ل\\.\\s?ل|ريال|درهم|جنيه"
    // Trailing guard so "USD" can never be mistaken for a truncated prefix of
    // "USDT" (or any other longer token) — without it, alternation matches
    // the first alternative that lets the rest of the pattern succeed, not
    // the longest one, so "USD" would win over "USDT" and silently drop the
    // "T". Mirrors the \b guard the priority-8 af rules already rely on.
    private const val CUR = "(?<currency>$CUR_BODY)(?![A-Za-z])"

    // The ungrouped (no thousands separator) alternative is capped at 9 digits
    // and fenced with (?<!\d)/(?!\d) so it can only ever match a WHOLE
    // standalone digit run, never a truncated prefix/suffix/substring of a
    // longer one -- without both fences, a 16-digit order/tracking number
    // sitting near a bare keyword rule (e.g. "We received your order
    // 7840200018688161!") gets grabbed as a multi-trillion "amount" instead
    // of being rejected outright. No real personal-finance amount is ever a
    // 10+ digit integer with no thousands separator, so this only ever
    // rejects non-money digit runs, never a real amount.
    private const val AMT = "(?<amount>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|(?<!\\d)\\d{1,9}(?:\\.\\d+)?)(?!\\d)"

    // Same shape as AMT but non-capturing, for amounts that must NOT become
    // the transaction amount (e.g. the cashback figure after a purchase).
    private const val AMT_NC = "(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|(?<!\\d)\\d{1,9}(?:\\.\\d+)?)(?!\\d)"

    // Merchant capture stops at punctuation and at "*" so a trailing "*617"
    // account marker is left for ACCTAIL rather than swallowed into the name.
    private const val MERCHANT_EN = "(?:\\s+(?:at|to|@|in)\\s+(?<merchant>[^.,;\\n*]{2,40}))?"
    private const val MERCHANT_AR = "(?:\\s+(?:في|لدى|من|إلى)\\s+(?<merchant>[^.,;\\n*]{2,40}))?"

    // Tabby's "Transaction of ... At {merchant} was successful." / "At
    // {merchant}. Your Tabby Card limit is now ..." layout: a lazy merchant
    // capture that stops right before either terminator, so it never runs on
    // into the following sentence (and never swallows a second amount later
    // in the message, which is what let a previous rule mis-fire on it).
    private const val MERCHANT_TABBY =
        "(?<merchant>[^.,;\\n*]{2,40}?)(?=\\s+was\\s+successful\\b|\\.)"

    /**
     * Optional, greedy account-number tail. Being a *greedy* optional group it
     * tries to find a number first and only falls back to matching nothing, so
     * it captures "*617", "account *963" (English) and "حساب(ك) رقم XXX810001"
     * (Arabic) when present, and stays out of the way otherwise.
     */
    private const val ACCTAIL =
        "(?:.{0,80}?(?:\\*\\s?|حساب\\S*\\s*رقم\\s*)(?<accountNumberSuffix>[Xx]*\\d{3,9}))?"

    private const val KW_DEBIT_EN =
        "\\b(?:spent|debited|debit|purchased?|purchase\\s+of|paid|payment\\s+of|withdrawn|withdrawal(?:\\s+of)?|charged|pos\\s+(?:txn|purchase)|dr)\\b"
    private const val KW_CREDIT_EN =
        "\\b(?:received|credited|credit|deposit(?:ed)?(?:\\s+of)?|refund(?:ed)?|salary|transfer\\s+(?:in|received)|incoming\\s+transfer|cr)\\b"

    private const val KW_DEBIT_AR =
        "(?:تم\\s+)?(?:خصم|دفع|سحب|شراء)|عملية\\s+شراء|مدين"
    private const val KW_CREDIT_AR =
        "(?:تم\\s+)?(?:إيداع|استلام|إضافة)|تحويل\\s+وارد|حوالة\\s+واردة|دائن|راتب"

    // Arabic account markers for the transfer rules below.
    private const val ACC_AR = "حساب\\S*\\s*رقم\\s*(?<accountNumberSuffix>[Xx]*\\d{3,9})"

    private fun rule(
        name: String,
        pattern: String,
        direction: TransactionDirection,
        priority: Int,
        category: TransactionCategory = TransactionCategory.NORMAL,
    ) =
        CompiledRule(
            ruleId = null,
            name = name,
            pattern = Regex(pattern, RegexOption.IGNORE_CASE),
            senderPattern = null,
            direction = direction,
            defaultCurrency = null,
            priority = priority,
            category = category,
        )

    val all: List<CompiledRule> = listOf(
        // --- English, keyword before amount, explicit currency (priority 10) ---
        rule("builtin.en.debit.cur-amt", "$KW_DEBIT_EN.{0,30}?$CUR\\s?$AMT$MERCHANT_EN$ACCTAIL", TransactionDirection.DEBIT, 10),
        rule("builtin.en.debit.amt-cur", "$KW_DEBIT_EN.{0,30}?$AMT\\s?$CUR$MERCHANT_EN$ACCTAIL", TransactionDirection.DEBIT, 10),
        rule("builtin.en.credit.cur-amt", "$KW_CREDIT_EN.{0,30}?$CUR\\s?$AMT$MERCHANT_EN$ACCTAIL", TransactionDirection.CREDIT, 10),
        rule("builtin.en.credit.amt-cur", "$KW_CREDIT_EN.{0,30}?$AMT\\s?$CUR$MERCHANT_EN$ACCTAIL", TransactionDirection.CREDIT, 10),

        // --- UAE card spend confirmations, no verb keyword (priority 10) ---
        // Issuers post "Trx. of AED68.00 on your card ending *876 at
        // MCDONALDS-54176, UAE is Approved. Avl. card bal is 17296.72. Trx
        // Date: 17/07/26 13:35". There is no spend keyword (spent/paid/debited)
        // and the card ending isn't registered for card-ending routing, so the
        // message matched nothing and was dropped. The "Trx. of <amount> … is
        // Approved" frame is the reliable marker; anchoring the amount right
        // after "Trx. of" keeps the trailing "Avl. card bal" figure from being
        // captured, and ACCTAIL/MERCHANT sit before the confirmation so both
        // the "*876" ending and the "at <merchant>" clause are recovered.
        // Only Approved transactions are recorded — a declined one never posted.
        rule(
            "builtin.en.debit.card-trx-approved",
            "\\bTrx\\.?\\s+of\\s+$CUR\\s?$AMT\\b$ACCTAIL$MERCHANT_EN.{0,60}?\\bApproved\\b",
            TransactionDirection.DEBIT,
            10,
        ),

        // --- Tabby purchase confirmations (priority 10) ---
        // "Transaction of AED 20.00 At Tips and Toes was successful. Your
        // available Tabby Card limit is AED 6676.12." / "...At Sephora ME.
        // Your Tabby Card limit is now AED 6813.02. You can now split your
        // purchase into 8 months." Must outrank the priority-8 "af" rules
        // below, which otherwise mis-fire on the second shape: the word
        // "purchase" later in the message falls inside their keyword window
        // and lets them grab the *remaining card limit* as the amount instead
        // of the real purchase amount.
        rule("builtin.en.debit.tabby.purchase", "Transaction\\s+of\\s+$CUR\\s?$AMT\\s+At\\s+$MERCHANT_TABBY$ACCTAIL", TransactionDirection.DEBIT, 10),

        // --- Purchases with a trailing cashback line (priority 12) ---
        // Kah-ching (KAST): "$27.71 spent at SHABIK ELECTRIC VEHICL ⏎ $0.42
        // cashback: 1.5% CASH". TextNormalizer collapses the newline, putting
        // the cashback amount inside the {0,30} forward window of the generic
        // keyword-first rules above — which then grab $0.42 as the amount,
        // when the real amount sits BEFORE the keyword. This amount-first rule
        // outranks them; the mandatory "<currency><amount> cashback" tail both
        // pins the merchant capture (which stops right before the cashback
        // figure) and keeps the rule from shadowing the generic amount-first
        // rules (priority 8) on ordinary messages.
        rule(
            "builtin.en.debit.af.cashback-tail",
            "$CUR\\s?$AMT\\s+$KW_DEBIT_EN$MERCHANT_EN.{0,40}?(?:$CUR_BODY)\\s?$AMT_NC\\s+cashback$ACCTAIL",
            TransactionDirection.DEBIT,
            12,
        ),

        // --- Transaction adjustments / partial refunds (priority 20) ---
        // Tabby "Transaction adjusted — Your CAREEM RIDE purchase was decreased
        // by AED 5.58": a partial refund. Recorded as a CREDIT so the money is
        // returned to the ledger. The rarer "increased by" is an extra charge
        // (DEBIT). Priority 20 puts these ahead of the generic keyword rules,
        // whose DEBIT keyword "purchase" would otherwise grab the adjusted
        // amount as a fresh spend. The required literal "Your " keeps the
        // notification title out of the (lazy) merchant capture.
        rule(
            "builtin.en.adjust.decrease",
            "Your\\s+(?<merchant>[^.,;\\n*]{2,40}?)\\s+(?:purchase|transaction|order|payment)\\s+was\\s+decreased\\s+by\\s+$CUR\\s?$AMT$ACCTAIL",
            TransactionDirection.CREDIT,
            20,
        ),
        rule(
            "builtin.en.adjust.increase",
            "Your\\s+(?<merchant>[^.,;\\n*]{2,40}?)\\s+(?:purchase|transaction|order|payment)\\s+was\\s+increased\\s+by\\s+$CUR\\s?$AMT$ACCTAIL",
            TransactionDirection.DEBIT,
            20,
        ),

        // --- Credit-card statement payment landing on the card (priority 15) ---
        // "Dear Customer, your payment of AED3600.00 on 7/20/2026 for card
        // ending with *876 has been credited. Thank you" -- the funding side
        // ("AED 3600.00 was debited from your account *963") is an ordinary
        // debit and parses fine on its own, but this credit side contains
        // "payment of" (a DEBIT keyword) before its actual direction word
        // ("credited") appears, and "credited" isn't one of
        // GenericExtractors' direction-override words -- so without this
        // rule it's misclassified as a second, bogus debit of the same
        // amount. Priority 15 outranks the generic priority-10 "payment of"
        // debit rule so this specific, more-informative shape wins the
        // match outright. IngestCapturedMessageUseCase tags anything matched
        // here as excludeFromStats + SpendingCategory.TRANSFERS, and
        // TransferMatcher then looks for the matching debit leg to link.
        rule(
            "builtin.en.transfer.card-payment-credit",
            "payment\\s+of\\s+$CUR\\s?$AMT.{0,20}?\\bfor\\s+card\\s+ending\\b$ACCTAIL.{0,30}?\\bcredited\\b",
            TransactionDirection.CREDIT,
            15,
        ),

        // --- Tabby cashback-earned notifications (priority 10) ---
        // "You earned AED 1.00! You got cashback with Tabby+. Your cashback
        // will be processed after next statement is paid." Tagged so the
        // Rewards tab can show real earned cashback separately from ordinary
        // credits (refunds, salary, transfers-in, etc).
        rule(
            "builtin.en.credit.tabby.cashback",
            "You\\s+earned\\s+$CUR\\s?$AMT$ACCTAIL",
            TransactionDirection.CREDIT,
            10,
            category = TransactionCategory.CASHBACK_EARNED,
        ),

        // --- Arabic, keyword before amount, explicit currency (priority 10) ---
        rule("builtin.ar.debit.amt-cur", "(?:$KW_DEBIT_AR).{0,30}?$AMT\\s?$CUR$MERCHANT_AR$ACCTAIL", TransactionDirection.DEBIT, 10),
        rule("builtin.ar.debit.cur-amt", "(?:$KW_DEBIT_AR).{0,30}?$CUR\\s?$AMT$MERCHANT_AR$ACCTAIL", TransactionDirection.DEBIT, 10),
        rule("builtin.ar.credit.amt-cur", "(?:$KW_CREDIT_AR).{0,30}?$AMT\\s?$CUR$MERCHANT_AR$ACCTAIL", TransactionDirection.CREDIT, 10),
        rule("builtin.ar.credit.cur-amt", "(?:$KW_CREDIT_AR).{0,30}?$CUR\\s?$AMT$MERCHANT_AR$ACCTAIL", TransactionDirection.CREDIT, 10),

        // --- Arabic transfers: amount then "from/to account رقم …" (priority 12) ---
        // Tried before the generic keyword rules so the account number is
        // extracted instead of being swallowed by the Arabic merchant group.
        // Outgoing transfer / deduction: "… AED45000.00 من حسابك رقم XXX810001"
        rule("builtin.ar.transfer.out", "$CUR\\s?$AMT.{0,40}?من\\s+$ACC_AR", TransactionDirection.DEBIT, 12),
        // Incoming transfer / salary / credit: "… AED62350.00 إلى/على حسابك رقم XXX810001"
        rule("builtin.ar.transfer.in", "$CUR\\s?$AMT.{0,40}?(?:إلى|على)\\s+$ACC_AR", TransactionDirection.CREDIT, 12),

        // --- English, amount before keyword, explicit currency (priority 8) ---
        // "AED 1895.00 was debited from your account *617"
        rule("builtin.en.debit.af.cur-amt", "$CUR\\s?$AMT\\b.{0,25}?$KW_DEBIT_EN$ACCTAIL", TransactionDirection.DEBIT, 8),
        rule("builtin.en.debit.af.amt-cur", "$AMT\\s?$CUR\\b.{0,25}?$KW_DEBIT_EN$ACCTAIL", TransactionDirection.DEBIT, 8),
        // "AED 1.00 was credited to your account *963" / "Your AED X purchase … " handled above
        rule("builtin.en.credit.af.cur-amt", "$CUR\\s?$AMT\\b.{0,25}?$KW_CREDIT_EN$ACCTAIL", TransactionDirection.CREDIT, 8),
        rule("builtin.en.credit.af.amt-cur", "$AMT\\s?$CUR\\b.{0,25}?$KW_CREDIT_EN$ACCTAIL", TransactionDirection.CREDIT, 8),

        // --- Signature-routed rules (no regex; see TransactionParser phase 1) ---
        // Binance Mastercard spend notifications vary their wording too much
        // for one stable regex; the card name itself is the reliable marker.
        // Amount/merchant come from GenericExtractors, and if no plausible
        // amount is present the message falls through to the regex rules.
        // NOTE: deliberately no Tabby signature rule here — Tabby messages are
        // already covered by the regex rules above and by the user's seeded
        // custom rules, which pin transactions to their accounts; a built-in
        // signature would intercept those messages and strip the pinning.
        CompiledRule(
            ruleId = null,
            name = "builtin.sig.binance.card",
            pattern = null,
            senderPattern = null,
            direction = TransactionDirection.DEBIT,
            defaultCurrency = null,
            priority = 10,
            signatures = listOf("Binance Mastercard"),
        ),

        // --- No currency in the message: fall back to the default currency (priority 0) ---
        // The (?<!\*) guard stops the fallback from grabbing a "*617" account
        // marker as the amount when the real amount had a currency the message
        // put elsewhere (handled by the amount-first rules above).
        rule("builtin.en.debit.bare", "$KW_DEBIT_EN.{0,20}?(?<!\\*)$AMT$MERCHANT_EN", TransactionDirection.DEBIT, 0),
        rule("builtin.en.credit.bare", "$KW_CREDIT_EN.{0,20}?(?<!\\*)$AMT$MERCHANT_EN", TransactionDirection.CREDIT, 0),
        rule("builtin.ar.debit.bare", "(?:$KW_DEBIT_AR).{0,20}?(?<!\\*)$AMT$MERCHANT_AR", TransactionDirection.DEBIT, 0),
        rule("builtin.ar.credit.bare", "(?:$KW_CREDIT_AR).{0,20}?(?<!\\*)$AMT$MERCHANT_AR", TransactionDirection.CREDIT, 0),
    )
}
