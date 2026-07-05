package com.xemmy.mmbank.data.local

import androidx.room.TypeConverter
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
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
}
