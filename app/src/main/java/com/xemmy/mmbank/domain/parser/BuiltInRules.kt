package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionDirection

/**
 * Built-in multi-language rules covering common English and Arabic bank
 * message layouts. They run at low priority so user-defined rules (default
 * priority 100) always win. All patterns match against [TextNormalizer]
 * output, so digits are already ASCII regardless of the source script.
 */
object BuiltInRules {

    // Restricted to known codes/symbols so stray 3-letter words are never
    // mistaken for a currency under IGNORE_CASE.
    private const val CUR =
        "(?<currency>USD|EUR|GBP|SAR|AED|EGP|KWD|QAR|OMR|BHD|JOD|LBP|IQD|TRY|INR|PKR|JPY|CNY|CHF|CAD|AUD" +
            "|[$€£₹₺¥]|ر\\.\\s?س|د\\.\\s?إ|ج\\.\\s?م|د\\.\\s?ك|ر\\.\\s?ق|ر\\.\\s?ع|د\\.\\s?ب|د\\.\\s?ا|ل\\.\\s?ل|ريال|درهم|جنيه)"

    private const val AMT = "(?<amount>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"

    private const val MERCHANT_EN = "(?:\\s+(?:at|to|@|in)\\s+(?<merchant>[^.,;\\n]{2,40}))?"
    private const val MERCHANT_AR = "(?:\\s+(?:في|لدى|من|إلى)\\s+(?<merchant>[^.,;\\n]{2,40}))?"

    private const val KW_DEBIT_EN =
        "\\b(?:spent|debited|debit|purchased?|purchase\\s+of|paid|payment\\s+of|withdrawn|withdrawal(?:\\s+of)?|charged|pos\\s+(?:txn|purchase))\\b"
    private const val KW_CREDIT_EN =
        "\\b(?:received|credited|credit|deposit(?:ed)?(?:\\s+of)?|refund(?:ed)?|salary|transfer\\s+(?:in|received)|incoming\\s+transfer)\\b"

    private const val KW_DEBIT_AR =
        "(?:تم\\s+)?(?:خصم|دفع|سحب|شراء)|عملية\\s+شراء|مدين"
    private const val KW_CREDIT_AR =
        "(?:تم\\s+)?(?:إيداع|استلام|إضافة)|تحويل\\s+وارد|حوالة\\s+واردة|دائن|راتب"

    private fun rule(name: String, pattern: String, direction: TransactionDirection, priority: Int) =
        CompiledRule(
            ruleId = null,
            name = name,
            pattern = Regex(pattern, RegexOption.IGNORE_CASE),
            senderPattern = null,
            direction = direction,
            defaultCurrency = null,
            priority = priority,
        )

    val all: List<CompiledRule> = listOf(
        // --- English, explicit currency (priority 10) ---
        rule("builtin.en.debit.cur-amt", "$KW_DEBIT_EN.{0,30}?$CUR\\s?$AMT$MERCHANT_EN", TransactionDirection.DEBIT, 10),
        rule("builtin.en.debit.amt-cur", "$KW_DEBIT_EN.{0,30}?$AMT\\s?$CUR$MERCHANT_EN", TransactionDirection.DEBIT, 10),
        rule("builtin.en.credit.cur-amt", "$KW_CREDIT_EN.{0,30}?$CUR\\s?$AMT$MERCHANT_EN", TransactionDirection.CREDIT, 10),
        rule("builtin.en.credit.amt-cur", "$KW_CREDIT_EN.{0,30}?$AMT\\s?$CUR$MERCHANT_EN", TransactionDirection.CREDIT, 10),

        // --- Arabic, explicit currency (priority 10) ---
        rule("builtin.ar.debit.amt-cur", "(?:$KW_DEBIT_AR).{0,30}?$AMT\\s?$CUR$MERCHANT_AR", TransactionDirection.DEBIT, 10),
        rule("builtin.ar.debit.cur-amt", "(?:$KW_DEBIT_AR).{0,30}?$CUR\\s?$AMT$MERCHANT_AR", TransactionDirection.DEBIT, 10),
        rule("builtin.ar.credit.amt-cur", "(?:$KW_CREDIT_AR).{0,30}?$AMT\\s?$CUR$MERCHANT_AR", TransactionDirection.CREDIT, 10),
        rule("builtin.ar.credit.cur-amt", "(?:$KW_CREDIT_AR).{0,30}?$CUR\\s?$AMT$MERCHANT_AR", TransactionDirection.CREDIT, 10),

        // --- No currency in the message: fall back to the default currency (priority 0) ---
        rule("builtin.en.debit.bare", "$KW_DEBIT_EN.{0,20}?$AMT$MERCHANT_EN", TransactionDirection.DEBIT, 0),
        rule("builtin.en.credit.bare", "$KW_CREDIT_EN.{0,20}?$AMT$MERCHANT_EN", TransactionDirection.CREDIT, 0),
        rule("builtin.ar.debit.bare", "(?:$KW_DEBIT_AR).{0,20}?$AMT$MERCHANT_AR", TransactionDirection.DEBIT, 0),
        rule("builtin.ar.credit.bare", "(?:$KW_CREDIT_AR).{0,20}?$AMT$MERCHANT_AR", TransactionDirection.CREDIT, 0),
    )
}
