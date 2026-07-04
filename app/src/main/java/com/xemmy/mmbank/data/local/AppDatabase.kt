package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.AssetBalanceDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        ParsingRuleEntity::class,
        AssetBalanceEntity::class,
        ExchangeRateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun parsingRuleDao(): ParsingRuleDao
    abstract fun assetBalanceDao(): AssetBalanceDao
    abstract fun exchangeRateDao(): ExchangeRateDao

    companion object {
        /**
         * v1 -> v2: account-number tracking. Adds the detected number to each
         * captured transaction and a configurable identifying number to accounts,
         * so transactions can be auto-assigned to the right account/card.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountNumberSuffix TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN accountNumber TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mmbank.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
