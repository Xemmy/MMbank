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
    // mistaken for a currency under IGNORE_CASE.
    private const val CUR =
        "(?<currency>USD|EUR|GBP|SAR|AED|EGP|KWD|QAR|OMR|BHD|JOD|LBP|IQD|TRY|INR|PKR|JPY|CNY|CHF|CAD|AUD" +
            "|[$€£₹₺¥]|ر\\.\\s?س|د\\.\\s?إ|ج\\.\\s?م|د\\.\\s?ك|ر\\.\\s?ق|ر\\.\\s?ع|د\\.\\s?ب|د\\.\\s?ا|ل\\.\\s?ل|ريال|درهم|جنيه)"

    private const val AMT = "(?<amount>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"

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
