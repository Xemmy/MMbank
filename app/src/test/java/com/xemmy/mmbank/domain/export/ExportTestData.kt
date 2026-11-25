package com.xemmy.mmbank.domain.export

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

internal val UTC: ZoneId = ZoneId.of("UTC")

internal fun millis(date: LocalDate = LocalDate.of(2026, 11, 20), hour: Int = 9, minute: Int = 5): Long =
    date.atTime(hour, minute).atZone(UTC).toInstant().toEpochMilli()

internal fun testTx(
    id: Long = 1,
    amount: String = "46.12",
    currency: String = "AED",
    direction: TransactionDirection = TransactionDirection.DEBIT,
    kind: TransactionKind = TransactionKind.defaultFor(direction),
    merchant: String? = "Carrefour",
    accountId: Long? = 1,
    categoryId: Long? = null,
    note: String? = null,
    person: String? = null,
    billRef: String? = null,
    excluded: Boolean = false,
) = Transaction(
    id = id,
    accountId = accountId,
    amount = BigDecimal(amount),
    currencyCode = currency,
    direction = direction,
    merchant = merchant,
    source = TransactionSource.SMS,
    timestampMillis = millis(),
    kind = kind,
    categoryId = categoryId,
    note = note,
    person = person,
    billRef = billRef,
    excludeFromStats = excluded,
)

internal fun testCategory(id: Long, name: String, parent: Long? = null) =
    Category(id = id, name = name, icon = "x", colorHex = "#000000", parentCategoryId = parent)

internal fun testRow(
    id: Long = 1,
    amount: String = "-46.12",
    merchant: String? = "Carrefour",
    note: String? = null,
    tags: List<String> = emptyList(),
    base: String? = null,
) = ExportRow(
    id = id,
    timestampMillis = millis(),
    kind = TransactionKind.SPEND,
    direction = TransactionDirection.DEBIT,
    amount = BigDecimal(amount),
    currency = "AED",
    amountInBase = base?.let(::BigDecimal),
    merchant = merchant,
    category = null,
    parentCategory = null,
    account = "Main",
    person = null,
    tags = tags,
    note = note,
    source = "SMS",
    sender = null,
    billRef = null,
    excludedFromStats = false,
    historyOnly = false,
    linkedTransactionId = null,
    refundOfId = null,
    reportedBalance = null,
)
