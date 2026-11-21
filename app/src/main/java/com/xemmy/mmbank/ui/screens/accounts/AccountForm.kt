package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Parses an amount the way people type it: "1,250.50", " -300 ", "2 000".
 * Null for blank or unparseable input. Shared by the account form and the
 * balance-adjust dialog so the two never disagree about what "1,000" means.
 */
internal fun parseMoneyInput(raw: String): BigDecimal? =
    com.xemmy.mmbank.util.AmountInput.parse(raw)

/** Human name for an account type, for pickers and labels. */
internal fun accountTypeLabel(type: AccountType): String = when (type) {
    AccountType.CHECKING -> "Current / checking"
    AccountType.SAVINGS -> "Savings"
    AccountType.CASH -> "Cash wallet"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.LOAN -> "Loan"
    AccountType.CRYPTO_SPOT -> "Crypto"
    AccountType.INVESTMENT -> "Investment"
}

/** Which optional sections the account form shows for a type. */
internal fun AccountType.hasCards(): Boolean =
    this == AccountType.CHECKING || this == AccountType.SAVINGS || this == AccountType.CREDIT_CARD

internal fun AccountType.isCreditCard(): Boolean = this == AccountType.CREDIT_CARD

internal fun AccountType.isLoan(): Boolean = this == AccountType.LOAN

/**
 * Everything the account dialog collects, as typed. Pure, so validation and
 * the "which fields belong to which type" rule are unit tested rather than
 * buried in a composable.
 */
internal data class AccountFormInput(
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingBalance: String,
    val accountNumber: String = "",
    val cardEndings: String = "",
    val bankName: String = "",
    val accountTitle: String = "",
    val iban: String = "",
    val swift: String = "",
    val notes: String = "",
    // Credit card
    val creditLimit: String = "",
    val statementDay: String = "",
    // Credit card (payment due) and loan (installment due)
    val dueDay: String = "",
    // Loan
    val outstanding: String = "",
    val nextInstallment: String = "",
    val profitRate: String = "",
    val maturity: String = "",
    val loanNotes: String = "",
)

internal enum class AccountField {
    NAME, CURRENCY, OPENING, CREDIT_LIMIT, STATEMENT_DAY, DUE_DAY, OUTSTANDING, INSTALLMENT, PROFIT_RATE, MATURITY,
}

internal object AccountFormValidator {

    /** Field -> problem, for every field that's filled in wrong. Empty = savable. */
    fun errors(input: AccountFormInput): Map<AccountField, String> = buildMap {
        if (input.name.isBlank()) put(AccountField.NAME, "Give it a name")
        if (!input.currency.trim().matches(Regex("[A-Za-z]{3}"))) put(AccountField.CURRENCY, "3-letter code, e.g. AED")
        if (parseMoneyInput(input.openingBalance.ifBlank { "0" }) == null) put(AccountField.OPENING, "Not a number")

        if (input.type.isCreditCard()) {
            if (input.creditLimit.isNotBlank()) {
                val limit = parseMoneyInput(input.creditLimit)
                if (limit == null || limit.signum() <= 0) put(AccountField.CREDIT_LIMIT, "A positive amount")
            }
            if (!isDayOrBlank(input.statementDay)) put(AccountField.STATEMENT_DAY, "Day 1-31")
        }
        if (input.type.isCreditCard() || input.type.isLoan()) {
            if (!isDayOrBlank(input.dueDay)) put(AccountField.DUE_DAY, "Day 1-31")
        }
        if (input.type.isLoan()) {
            if (input.outstanding.isNotBlank() && parseMoneyInput(input.outstanding) == null) {
                put(AccountField.OUTSTANDING, "Not a number")
            }
            if (input.nextInstallment.isNotBlank() && parseMoneyInput(input.nextInstallment) == null) {
                put(AccountField.INSTALLMENT, "Not a number")
            }
            if (input.profitRate.isNotBlank()) {
                val rate = input.profitRate.trim().removeSuffix("%").trim().toDoubleOrNull()
                if (rate == null || rate < 0) put(AccountField.PROFIT_RATE, "A rate like 5.49")
            }
            if (input.maturity.isNotBlank() && parseIsoDate(input.maturity) == null) {
                put(AccountField.MATURITY, "Use YYYY-MM-DD")
            }
        }
    }

    /**
     * [existing] updated from [input]. Fields that don't apply to the chosen
     * type are cleared, so switching a "loan" to a current account doesn't
     * leave a hidden outstanding balance behind. Call only when [errors] is
     * empty.
     */
    fun apply(existing: Account, input: AccountFormInput, zone: ZoneId = ZoneId.systemDefault()): Account {
        val type = input.type
        fun text(s: String) = s.trim().takeIf { it.isNotEmpty() }
        fun day(s: String) = s.trim().toIntOrNull()?.takeIf { it in 1..31 }
        val card = type.isCreditCard()
        val loan = type.isLoan()
        return existing.copy(
            name = input.name.trim(),
            type = type,
            currencyCode = input.currency.trim().uppercase(),
            openingBalance = parseMoneyInput(input.openingBalance) ?: BigDecimal.ZERO,
            notes = text(input.notes),
            accountNumber = text(input.accountNumber),
            cardEndings = if (type.hasCards()) normalizeCardEndings(input.cardEndings) else null,
            bankName = text(input.bankName),
            accountTitle = text(input.accountTitle),
            iban = text(input.iban),
            swift = text(input.swift),
            creditLimit = if (card) parseMoneyInput(input.creditLimit) else null,
            statementCycleDay = if (card) day(input.statementDay) else null,
            dueDayOfMonth = if (card || loan) day(input.dueDay) else null,
            outstandingBalance = if (loan) parseMoneyInput(input.outstanding) else null,
            nextInstallmentAmount = if (loan) parseMoneyInput(input.nextInstallment) else null,
            profitRatePa = if (loan) input.profitRate.trim().removeSuffix("%").trim().toDoubleOrNull() else null,
            maturityDateMillis = if (loan) {
                parseIsoDate(input.maturity)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            } else {
                null
            },
            loanNotes = if (loan) text(input.loanNotes) else null,
        )
    }

    /** "4321, *8765 ,  12" -> "4321, 8765": digits only, at least 3, de-duplicated. Null when nothing's left. */
    fun normalizeCardEndings(raw: String): String? =
        raw.split(',', ';', '/', '\n', ' ')
            .map { it.filter(Char::isDigit) }
            .filter { it.length >= 3 }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

    private fun isDayOrBlank(s: String) = s.isBlank() || s.trim().toIntOrNull()?.let { it in 1..31 } == true

    private fun parseIsoDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()
}
