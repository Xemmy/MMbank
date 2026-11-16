package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MAX_LENGTH
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MIN_LENGTH
import com.xemmy.mmbank.util.AmountInput
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything the add/edit transaction screen holds while it is open, as plain data so the rules
 * are testable on the JVM and the whole form survives rotation ([toSaveable] / [fromSaveable]).
 */
data class TransactionEditorForm(
    val amount: String = "",
    val currency: String,
    val kind: TransactionKind = TransactionKind.SPEND,
    val merchant: String = "",
    val accountId: Long? = null,
    val person: String? = null,
    /** null = follow the merchant-derived suggestion; a value = an explicit choice. */
    val categoryOverride: Long? = null,
    val excludeFromStats: Boolean = false,
    val date: LocalDate,
    val time: LocalTime,
    val fromAccountId: Long? = null,
    val toAccountId: Long? = null,
    val toAmount: String = "",
    val outside: TransferLegs.OutsideParty = TransferLegs.OutsideParty.MY_OTHER_ACCOUNT,
) {
    /** Only used for a kind that goes either way: an edited transfer leg keeps the side it was on. */
    fun direction(existing: Transaction?): TransactionDirection =
        kind.fixedDirection ?: existing?.direction ?: TransactionDirection.DEBIT

    /** Adding a transfer or card payment means two accounts and two legs. */
    fun transferMode(isEdit: Boolean): Boolean = !isEdit && kind.isInternalMove

    val amountValue: BigDecimal? get() = AmountInput.parse(amount)
    val toAmountValue: BigDecimal? get() = AmountInput.parse(toAmount)

    fun crossCurrency(accounts: List<Account>, isEdit: Boolean): Boolean {
        if (!transferMode(isEdit)) return false
        val from = accounts.firstOrNull { it.id == fromAccountId }
        val to = accounts.firstOrNull { it.id == toAccountId }
        return from != null && to != null && !from.currencyCode.equals(to.currencyCode, ignoreCase = true)
    }

    private fun endpoint(account: Account?) = account?.let { TransferLegs.Endpoint(it.id, it.name, it.type, it.currencyCode) }

    fun transferDraft(accounts: List<Account>, isEdit: Boolean, zone: ZoneId): TransferLegs.Draft? {
        if (!transferMode(isEdit)) return null
        val value = amountValue ?: return null
        return TransferLegs.Draft(
            from = endpoint(accounts.firstOrNull { it.id == fromAccountId }),
            to = endpoint(accounts.firstOrNull { it.id == toAccountId }),
            amount = value,
            toAmount = if (crossCurrency(accounts, isEdit)) toAmountValue else null,
            timestampMillis = timestamp(zone),
            note = merchant,
            person = person,
            outside = outside,
        )
    }

    /** What stops a transfer from being saved, or null. */
    fun transferError(accounts: List<Account>, isEdit: Boolean, zone: ZoneId): String? {
        if (!transferMode(isEdit)) return null
        val draft = transferDraft(accounts, isEdit, zone)
        return draft?.let(TransferLegs::validate)
            ?: if (crossCurrency(accounts, isEdit) && toAmountValue == null) "Enter the amount received" else null
    }

    fun valid(accounts: List<Account>, isEdit: Boolean, zone: ZoneId): Boolean {
        val value = amountValue
        if (value == null || value.signum() <= 0) return false
        if (!transferMode(isEdit) && currency.trim().length !in CURRENCY_CODE_MIN_LENGTH..CURRENCY_CODE_MAX_LENGTH) return false
        if (transferMode(isEdit)) {
            val draft = transferDraft(accounts, isEdit, zone) ?: return false
            return transferError(accounts, isEdit, zone) == null && draft.amount.signum() > 0
        }
        return true
    }

    fun timestamp(zone: ZoneId): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    /** Primitive form for `rememberSaveable`. */
    fun toSaveable(): List<Any?> = listOf(
        amount, currency, kind.name, merchant, accountId, person, categoryOverride, excludeFromStats,
        date.toString(), time.toString(), fromAccountId, toAccountId, toAmount, outside.name,
    )

    companion object {
        /** A blank form for a new entry, or one filled from [existing] keeping its exact time of day. */
        fun initial(existing: Transaction?, defaultCurrency: String, zone: ZoneId, now: LocalDateTime): TransactionEditorForm {
            val at = existing?.let { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDateTime() } ?: now
            return TransactionEditorForm(
                amount = existing?.amount?.stripTrailingZeros()?.toPlainString() ?: "",
                currency = existing?.currencyCode ?: defaultCurrency,
                kind = existing?.kind ?: TransactionKind.SPEND,
                merchant = existing?.merchant.orEmpty(),
                accountId = existing?.accountId,
                person = existing?.person,
                categoryOverride = existing?.categoryId,
                excludeFromStats = existing?.excludeFromStats ?: false,
                date = at.toLocalDate(),
                time = if (existing != null) at.toLocalTime() else at.toLocalTime().withSecond(0).withNano(0),
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromSaveable(values: List<Any?>): TransactionEditorForm? = runCatching {
            TransactionEditorForm(
                amount = values[0] as String,
                currency = values[1] as String,
                kind = TransactionKind.valueOf(values[2] as String),
                merchant = values[3] as String,
                accountId = (values[4] as Number?)?.toLong(),
                person = values[5] as String?,
                categoryOverride = (values[6] as Number?)?.toLong(),
                excludeFromStats = values[7] as Boolean,
                date = LocalDate.parse(values[8] as String),
                time = LocalTime.parse(values[9] as String),
                fromAccountId = (values[10] as Number?)?.toLong(),
                toAccountId = (values[11] as Number?)?.toLong(),
                toAmount = values[12] as String,
                outside = TransferLegs.OutsideParty.valueOf(values[13] as String),
            )
        }.getOrNull()
    }
}
