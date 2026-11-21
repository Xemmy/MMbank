package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Telecom and utility bills (du, e&, DEWA, ADDC...): the amount changes every
 * month, the user pays on whatever day suits them, and one provider can bill
 * two lines. The subscription radar clusters by amount and day rhythm, so it
 * can never fit these; this detector buckets payments by **calendar month**
 * instead and leaves the amount free.
 */
object BillDetector {

    private data class Provider(val key: String, val pattern: Regex)

    private fun provider(key: String, pattern: String) = Provider(key, Regex(pattern, RegexOption.IGNORE_CASE))

    private val PROVIDERS = listOf(
        provider("etisalat", "etisalat|(^|[^a-z])e&(\\s|$|[^a-z])|emirates telecommunications"),
        provider("du", "^du\\b|\\bdu\\s+(telecom|bill|postpaid|prepaid|home|mobile)|emirates integrated telecom|\\beitc\\b|du\\.ae"),
        provider("dewa", "\\bdewa\\b|dubai electricity"),
        provider("addc", "\\baddc\\b|\\btaqa\\b|abu dhabi distribution"),
        provider("aadc", "\\baadc\\b|al ain distribution"),
        provider("sewa", "\\bsewa\\b|sharjah electricity"),
        provider("fewa", "\\bfewa\\b|etihad water"),
        provider("empower", "\\bempower\\b"),
        provider("tabreed", "\\btabreed\\b|district cooling"),
        provider("lootah", "lootah"),
    )

    /** Carrier billing ("DU Google Payment") is an app-store purchase, not the phone bill. */
    private val CARRIER_BILLING = Regex("google|apple|itunes|play store|app store|netflix|osn|shahid", RegexOption.IGNORE_CASE)

    /** Canonical provider key ("etisalat", "du", "dewa"...) or null when [merchant] is not a known biller. */
    fun providerKey(merchant: String?): String? {
        val name = merchant?.trim().orEmpty()
        if (name.isEmpty() || CARRIER_BILLING.containsMatchIn(name)) return null
        return PROVIDERS.firstOrNull { it.pattern.containsMatchIn(name) }?.key
    }

    fun isBiller(merchant: String?): Boolean = providerKey(merchant) != null

    private val MOBILE = Regex("(?:\\+?971[\\s-]?|\\b0)(5\\d)[\\s-]?(\\d{3})[\\s-]?(\\d{4})\\b")
    private val SERVICE_NUMBER = Regex(
        "(?:contract|customer|service|line|subscriber|premise|premises)\\s*(?:no\\.?|number|id|#)?\\s*[:#]?\\s*(\\d{5,})",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Last 4 digits of the phone line or service/contract number the SMS names, or null.
     * Masked card and bank account numbers ("card XXXX1234", "a/c ...") are never read.
     */
    fun accountRef(rawMessage: String?): String? {
        val text = rawMessage ?: return null
        MOBILE.find(text)?.let { return it.groupValues[3] }
        return SERVICE_NUMBER.find(text)?.groupValues?.get(1)?.takeLast(4)
    }

    /** The line a payment was for: stored on the row (merged receipt) or read from its own SMS. */
    fun lineOf(tx: Transaction): String? = tx.billRef ?: accountRef(tx.rawMessage)

    private val BILL_WORDING = Regex("\\bbill\\b|invoice|postpaid", RegexOption.IGNORE_CASE)

    data class DetectedBill(
        val providerKey: String,
        val displayName: String,
        val accountRef: String?,
        val currencyCode: String,
        /** Median of the monthly totals. */
        val typicalAmount: BigDecimal,
        val minAmount: BigDecimal,
        val maxAmount: BigDecimal,
        /** Median number of payments in a month that had any (2 = two lines without a line number in the SMS). */
        val paymentsPerMonth: Int,
        /** Median day of the month of the first payment each month. */
        val typicalDay: Int,
        val lastPaidMillis: Long,
    ) {
        /** Identity used to match a suggestion to a tracked bill or a "Not a bill" choice. */
        val id: String get() = "$providerKey|${currencyCode.uppercase()}|${accountRef.orEmpty()}"
    }

    /**
     * Recurring bills: a provider (and line, when the SMS names one) paid in at least
     * 3 of the last 4 calendar months, or 2 consecutive months when the SMS says "bill".
     */
    fun detect(transactions: List<Transaction>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<DetectedBill> {
        val window = (0L..3L).map { YearMonth.from(today).minusMonths(it) }.toSet()
        // A payment recorded twice (bank SMS + provider receipt) counts once, under the receipt's line number.
        val duplicates = BillDuplicateFinder.pairs(transactions)
        val dropped = duplicates.map { it.remove.id }.toSet()
        val refFor = duplicates.associate { it.keep.id to it.ref }
        return transactions
            .asSequence()
            .filter { it.kind == TransactionKind.SPEND && !it.excludeFromStats && it.direction == TransactionDirection.DEBIT }
            .filter { it.id !in dropped }
            .mapNotNull { tx -> providerKey(tx.merchant)?.let { key -> key to tx } }
            .groupBy({ (key, tx) -> Triple(key, tx.currencyCode.uppercase(), refFor[tx.id] ?: lineOf(tx)) }, { it.second })
            .mapNotNull { (group, txs) -> build(group.first, group.second, group.third, txs, window, zone) }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.accountRef.orEmpty() }))
    }

    private fun build(
        key: String,
        currency: String,
        ref: String?,
        txs: List<Transaction>,
        window: Set<YearMonth>,
        zone: ZoneId,
    ): DetectedBill? {
        fun month(tx: Transaction) = YearMonth.from(Instant.ofEpochMilli(tx.timestampMillis).atZone(zone))
        val byMonth = txs.groupBy(::month)
        val recent = byMonth.keys.filter { it in window }.sorted()
        val hinted = txs.any { it.rawMessage?.let(BILL_WORDING::containsMatchIn) == true }
        val consecutive = recent.zipWithNext().any { (a, b) -> a.plusMonths(1) == b }
        val enough = recent.size >= 3 || (hinted && recent.size >= 2 && consecutive)
        if (!enough) return null

        val totals = byMonth.values.map { list -> list.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount } }
        val firstDays = byMonth.values.map { list ->
            Instant.ofEpochMilli(list.minOf { it.timestampMillis }).atZone(zone).dayOfMonth
        }
        val latest = txs.maxBy { it.timestampMillis }
        return DetectedBill(
            providerKey = key,
            displayName = latest.merchant!!.trim(),
            accountRef = ref,
            currencyCode = currency,
            typicalAmount = median(totals).setScale(2, RoundingMode.HALF_UP),
            minAmount = totals.min().setScale(2, RoundingMode.HALF_UP),
            maxAmount = totals.max().setScale(2, RoundingMode.HALF_UP),
            paymentsPerMonth = medianInt(byMonth.values.map { it.size }),
            typicalDay = medianInt(firstDays),
            lastPaidMillis = latest.timestampMillis,
        )
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
    }

    /** Lower median, so "1 or 2 payments" reads as the smaller, safer number. */
    private fun medianInt(values: List<Int>): Int = values.sorted()[(values.size - 1) / 2]

    /** A monthly bill from a suggestion: due on its usual day, the first such date after the last payment that is not past. */
    fun toBill(detected: DetectedBill, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Bill {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val lastPaid = Instant.ofEpochMilli(detected.lastPaidMillis).atZone(zone).toLocalDate()
        fun dueIn(ym: YearMonth) = ym.atDay(minOf(detected.typicalDay, ym.lengthOfMonth()))
        var month = YearMonth.from(lastPaid).plusMonths(1)
        while (dueIn(month).isBefore(today)) month = month.plusMonths(1)
        val name = detected.displayName + (detected.accountRef?.let { " ·$it" } ?: "")
        return Bill(
            name = name,
            amount = detected.typicalAmount,
            currencyCode = detected.currencyCode,
            recurrence = BillRecurrence.MONTHLY,
            nextDueDateMillis = dueIn(month).atStartOfDay(zone).toInstant().toEpochMilli(),
            createdAt = nowMillis,
            dueDayAnchor = detected.typicalDay,
            providerKey = detected.providerKey,
            accountRef = detected.accountRef,
            lastPaidMillis = detected.lastPaidMillis,
        )
    }

    /** True when [bill] already tracks [detected] (same provider and line). */
    fun tracks(bill: Bill, detected: DetectedBill): Boolean =
        bill.providerKey == detected.providerKey && bill.accountRef == detected.accountRef &&
            bill.currencyCode.equals(detected.currencyCode, ignoreCase = true)
}

/** Marks a provider-linked bill paid when its payment is captured: any amount, any day within the cycle. */
object BillAutoMatch {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** A payment counts for the cycle due on D from D-20 days to D+25 days. */
    private const val EARLY_DAYS = 20L
    private const val LATE_DAYS = 25L

    /** Two payments of one bill closer than this belong to the same cycle (the second is ignored). */
    private const val MIN_DAYS_BETWEEN_PAYMENTS = 20L

    /** The bill [tx] pays, advanced one cycle with the paid amount and time recorded; null when it pays none. */
    fun match(bills: List<Bill>, tx: Transaction, zone: ZoneId = ZoneId.systemDefault()): Bill? {
        if (tx.kind != TransactionKind.SPEND || tx.direction != TransactionDirection.DEBIT || tx.excludeFromStats) return null
        val key = BillDetector.providerKey(tx.merchant) ?: return null
        val ref = BillDetector.lineOf(tx)
        val sameProvider = bills.filter {
            it.providerKey == key && it.recurrence != null && it.currencyCode.equals(tx.currencyCode, ignoreCase = true)
        }
        // A bill for one line never takes a payment that names another line.
        val eligible = sameProvider.filter { b -> b.accountRef == null || ref == null || b.accountRef == ref }
        val exact = eligible.filter { ref != null && it.accountRef == ref }
        // A number-less payment (bank SMS only) belongs to the provider's number-less bill when there is one.
        val pool = exact.ifEmpty { if (ref == null) eligible.filter { it.accountRef == null }.ifEmpty { eligible } else eligible }
        // ...unless it is the bank's copy of a numbered line's payment the provider SMS already paid.
        if (ref == null && sameProvider.any { b ->
                b.accountRef != null && b.lastPaidMillis != null &&
                    kotlin.math.abs(tx.timestampMillis - b.lastPaidMillis) <= DAY_MILLIS &&
                    b.amount.compareTo(tx.amount.setScale(2, RoundingMode.HALF_UP)) == 0
            }
        ) return null

        return pool
            .mapNotNull { bill -> catchUp(bill, tx.timestampMillis, zone)?.let { bill to it } }
            .filter { (_, due) -> paysCycle(due, tx.timestampMillis) }
            .filter { (bill, _) -> bill.lastPaidMillis == null || tx.timestampMillis - bill.lastPaidMillis >= MIN_DAYS_BETWEEN_PAYMENTS * DAY_MILLIS }
            .minByOrNull { (_, due) -> kotlin.math.abs(due.nextDueDateMillis - tx.timestampMillis) }
            ?.let { (_, due) ->
                val advanced = BillAdvancer.advance(due, zone) ?: return null
                advanced.copy(amount = tx.amount.setScale(2, RoundingMode.HALF_UP), lastPaidMillis = tx.timestampMillis)
            }
    }

    /**
     * A payment's line became known after it was matched (the bank SMS arrived first and paid the
     * number-less bill; the provider receipt then named the line). Returns the bills to save: the
     * wrongly paid number-less bill put back one cycle, and the numbered line's bill paid instead.
     */
    fun reassign(bills: List<Bill>, tx: Transaction, zone: ZoneId = ZoneId.systemDefault()): List<Bill> {
        val key = BillDetector.providerKey(tx.merchant) ?: return emptyList()
        val ref = BillDetector.lineOf(tx) ?: return emptyList()
        val changed = mutableListOf<Bill>()
        val wrong = bills.firstOrNull {
            it.providerKey == key && it.accountRef == null && it.lastPaidMillis == tx.timestampMillis
        }
        if (wrong != null) changed += wrong.copy(nextDueDateMillis = retreat(wrong, zone), lastPaidMillis = null)
        val numbered = bills.filter { it.providerKey == key && it.accountRef == ref }
        if (numbered.none { it.lastPaidMillis == tx.timestampMillis }) {
            match(numbered, tx, zone)?.let { changed += it }
        }
        return changed
    }

    /** One cycle back (the inverse of [BillAdvancer.advance] for a monthly-or-longer bill). */
    private fun retreat(bill: Bill, zone: ZoneId): Long {
        val period = bill.recurrence?.period ?: return bill.nextDueDateMillis
        var prev = Instant.ofEpochMilli(bill.nextDueDateMillis).atZone(zone).minus(period)
        val anchor = bill.dueDayAnchor
        if (period.toTotalMonths() > 0 && anchor != null) {
            prev = prev.withDayOfMonth(minOf(anchor, prev.toLocalDate().lengthOfMonth()))
        }
        return prev.toInstant().toEpochMilli()
    }

    private fun paysCycle(bill: Bill, paidAt: Long): Boolean =
        paidAt >= bill.nextDueDateMillis - EARLY_DAYS * DAY_MILLIS && paidAt <= bill.nextDueDateMillis + LATE_DAYS * DAY_MILLIS

    /** Moves a bill whose due date is long past (missed months) forward to the cycle the payment belongs to. */
    private fun catchUp(bill: Bill, paidAt: Long, zone: ZoneId): Bill? {
        var current = bill
        var steps = 0
        while (paidAt > current.nextDueDateMillis + LATE_DAYS * DAY_MILLIS && steps < 36) {
            current = BillAdvancer.advance(current, zone) ?: return null
            steps++
        }
        return current
    }
}

/** A provider bill's past payments, and the captured ones that could still be attached to it. */
object BillHistory {

    private const val LOOKBACK_DAYS = 365L

    private fun isProviderSpend(bill: Bill, tx: Transaction): Boolean =
        bill.providerKey != null &&
            tx.kind == TransactionKind.SPEND && !tx.excludeFromStats && tx.direction == TransactionDirection.DEBIT &&
            BillDetector.providerKey(tx.merchant) == bill.providerKey &&
            tx.currencyCode.equals(bill.currencyCode, ignoreCase = true)

    /** Payments that count for [bill]: its own line when it has a number, else the number-less ones. Newest first. */
    fun paymentsFor(bill: Bill, transactions: List<Transaction>): List<Transaction> =
        transactions
            .filter { isProviderSpend(bill, it) && BillDetector.lineOf(it) == bill.accountRef }
            .sortedByDescending { it.timestampMillis }

    /**
     * Number-less payments of the same provider in the last year that a numbered bill could take
     * (their SMS names no line). Empty for a bill without a line, which already counts them all.
     */
    fun candidates(bill: Bill, transactions: List<Transaction>, nowMillis: Long): List<Transaction> {
        if (bill.accountRef == null) return emptyList()
        val since = nowMillis - LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        return transactions
            .filter { isProviderSpend(bill, it) && BillDetector.lineOf(it) == null && it.timestampMillis >= since }
            .sortedByDescending { it.timestampMillis }
    }
}

/**
 * Payments recorded twice before 0.137.0 merged them at capture: the bank's deduction SMS and the
 * provider's receipt (which names the line), same provider, same amount, within a day.
 */
object BillDuplicateFinder {

    private const val WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /** [keep] stays (the bank row, which carries the account), [remove] is the receipt copy, [ref] its line. */
    data class Duplicate(val keep: Transaction, val remove: Transaction, val ref: String)

    fun pairs(transactions: List<Transaction>): List<Duplicate> {
        val bills = transactions.filter {
            it.kind == TransactionKind.SPEND && !it.excludeFromStats && it.direction == TransactionDirection.DEBIT &&
                BillDetector.providerKey(it.merchant) != null
        }
        val numbered = bills.filter { BillDetector.lineOf(it) != null }
        val plain = bills.filter { BillDetector.lineOf(it) == null }.toMutableList()
        val result = mutableListOf<Duplicate>()
        for (receipt in numbered.sortedBy { it.timestampMillis }) {
            val key = BillDetector.providerKey(receipt.merchant)
            val bank = plain
                .filter {
                    BillDetector.providerKey(it.merchant) == key &&
                        it.currencyCode.equals(receipt.currencyCode, ignoreCase = true) &&
                        it.amount.compareTo(receipt.amount) == 0 &&
                        kotlin.math.abs(it.timestampMillis - receipt.timestampMillis) <= WINDOW_MILLIS
                }
                .minByOrNull { kotlin.math.abs(it.timestampMillis - receipt.timestampMillis) } ?: continue
            plain.remove(bank)
            // Keep whichever row is filed into an account (normally the bank's), else the bank row.
            val keepBank = bank.accountId != null || receipt.accountId == null
            val keep = if (keepBank) bank else receipt
            val remove = if (keepBank) receipt else bank
            result += Duplicate(keep, remove, BillDetector.lineOf(receipt)!!)
        }
        return result
    }
}
