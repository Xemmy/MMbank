package com.xemmy.mmbank.data.local

import androidx.room.TypeConverter
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.SplitType
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal

/**
 * Amounts are stored as normalized plain-decimal TEXT (never scientific
 * notation, no trailing zeros). Because the same converter runs for query
 * parameters, string equality in SQL is a reliable amount comparison — this is
 * what the dedup query relies on.
 */
class Converters {

    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? =
        value?.stripTrailingZeros()?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    @TypeConverter
    fun directionToString(value: TransactionDirection?): String? = value?.name

    @TypeConverter
    fun stringToDirection(value: String?): TransactionDirection? =
        value?.let { TransactionDirection.valueOf(it) }

    @TypeConverter
    fun kindToString(value: TransactionKind?): String? = value?.name

    /** Lenient: an unknown name (a newer build's kind) reads back as null rather than crashing. */
    @TypeConverter
    fun stringToKind(value: String?): TransactionKind? =
        value?.let { n -> TransactionKind.entries.firstOrNull { it.name == n } }

    @TypeConverter
    fun reportedBalanceKindToString(value: ReportedBalanceKind?): String? = value?.name

    /** Lenient: an unknown name (a newer build's kind) reads back as null rather than crashing. */
    @TypeConverter
    fun stringToReportedBalanceKind(value: String?): ReportedBalanceKind? =
        value?.let { n -> ReportedBalanceKind.entries.firstOrNull { it.name == n } }

    @TypeConverter
    fun sourceToString(value: TransactionSource?): String? = value?.name

    @TypeConverter
    fun stringToSource(value: String?): TransactionSource? =
        value?.let { TransactionSource.valueOf(it) }

    @TypeConverter
    fun accountTypeToString(value: AccountType?): String? = value?.name

    @TypeConverter
    fun stringToAccountType(value: String?): AccountType? =
        value?.let { AccountType.valueOf(it) }

    @TypeConverter
    fun categoryToString(value: TransactionCategory?): String? = value?.name

    @TypeConverter
    fun stringToCategory(value: String?): TransactionCategory? =
        value?.let { TransactionCategory.valueOf(it) }

    @TypeConverter
    fun spendingCategoryToString(value: SpendingCategory?): String? = value?.name

    @TypeConverter
    fun stringToSpendingCategory(value: String?): SpendingCategory? =
        value?.let { runCatching { SpendingCategory.valueOf(it) }.getOrDefault(SpendingCategory.UNCATEGORIZED) }

    @TypeConverter
    fun cadenceToString(value: SubscriptionCadence?): String? = value?.name

    @TypeConverter
    fun stringToCadence(value: String?): SubscriptionCadence? =
        value?.let { SubscriptionCadence.valueOf(it) }

    @TypeConverter
    fun billRecurrenceToString(value: BillRecurrence?): String? = value?.name

    @TypeConverter
    fun stringToBillRecurrence(value: String?): BillRecurrence? =
        value?.let { BillRecurrence.valueOf(it) }

    @TypeConverter
    fun splitTypeToString(value: SplitType?): String? = value?.name

    @TypeConverter
    fun stringToSplitType(value: String?): SplitType? =
        value?.let { SplitType.valueOf(it) }
}
