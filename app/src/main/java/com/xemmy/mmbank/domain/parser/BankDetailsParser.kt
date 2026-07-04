package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.ParsedBankDetails

/**
 * Extracts bank-account details from pasted free text such as a statement
 * header or a "share account details" block. It is label-driven (handling the
 * many ways banks phrase each field) with light regex fallbacks for IBAN,
 * SWIFT/BIC and currency when a line is unlabelled.
 */
object BankDetailsParser {

    private val KNOWN_CURRENCIES = setOf(
        "AED", "USD", "EUR", "GBP", "SAR", "QAR", "KWD", "BHD", "OMR", "EGP",
        "INR", "PKR", "JPY", "CNY", "CHF", "CAD", "AUD", "TRY",
    )

    // AE + 2 check digits + up to 30 alphanumerics (UAE IBANs are 23 chars, but
    // stay permissive for other countries too).
    private val IBAN_RE = Regex("\\b([A-Z]{2}\\d{2}[A-Z0-9]{11,30})\\b")
    // 8 or 11 char SWIFT/BIC: 4 bank + 2 country + 2 location (+ 3 branch).
    private val SWIFT_RE = Regex("\\b([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b")

    fun parse(text: String): ParsedBankDetails {
        var bankName: String? = null
        var accountTitle: String? = null
        var accountNumber: String? = null
        var iban: String? = null
        var swift: String? = null
        var currency: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val sep = line.indexOfFirst { it == ':' || it == '\t' }
            if (sep <= 0) continue
            val label = normalizeLabel(line.substring(0, sep))
            val value = line.substring(sep + 1).trim().trimStart(':').trim()
            if (value.isEmpty()) continue

            when {
                label.contains("iban") ->
                    iban = iban ?: value.replace(" ", "").uppercase()
                label.contains("swift") || label.contains("bic") ->
                    swift = swift ?: value.replace(" ", "").uppercase()
                label.contains("currency") || label == "ccy" ->
                    currency = currency ?: value.uppercase().take(3).takeIf { it.length == 3 }
                label.contains("accountnumber") || label.contains("accountno") ||
                    label.contains("accno") || label.contains("acno") || label == "account" ->
                    accountNumber = accountNumber ?: value.replace(" ", "")
                label.contains("accounttitle") || label.contains("accountholder") ||
                    label.contains("accountname") || label.contains("holder") || label == "title" || label == "name" ->
                    accountTitle = accountTitle ?: value
                label.contains("bankname") || label == "bank" ->
                    bankName = bankName ?: value
            }
        }

        // Fallbacks from the whole text for anything still missing.
        val upper = text.uppercase()
        if (iban == null) iban = IBAN_RE.find(upper)?.groupValues?.get(1)
        if (swift == null) {
            // Avoid re-matching a chunk of the IBAN as a SWIFT code.
            swift = SWIFT_RE.findAll(upper)
                .map { it.groupValues[1] }
                .firstOrNull { candidate -> iban == null || !iban!!.contains(candidate) }
        }
        if (currency == null) {
            currency = Regex("\\b([A-Z]{3})\\b").findAll(upper)
                .map { it.groupValues[1] }
                .firstOrNull { it in KNOWN_CURRENCIES }
        }

        return ParsedBankDetails(
            bankName = bankName,
            accountTitle = accountTitle,
            accountNumber = accountNumber,
            iban = iban,
            swift = swift,
            currencyCode = currency,
        )
    }

    private fun normalizeLabel(label: String): String =
        label.lowercase().filter { it.isLetterOrDigit() }
}
