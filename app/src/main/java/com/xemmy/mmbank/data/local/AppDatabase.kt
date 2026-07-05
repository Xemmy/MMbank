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
import com.xemmy.mmbank.data.local.dao.SenderLabelDao
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        ParsingRuleEntity::class,
        AssetBalanceEntity::class,
        ExchangeRateEntity::class,
        SenderLabelEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun parsingRuleDao(): ParsingRuleDao
    abstract fun assetBalanceDao(): AssetBalanceDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun senderLabelDao(): SenderLabelDao

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

        /**
         * v2 -> v3: bank details + loan fields on accounts, and a user-editable
         * sender-label table so "com.adib.mobile" can display as "ADIB".
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN bankName TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN accountTitle TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN iban TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN swift TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN outstandingBalance TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN nextInstallmentAmount TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN dueDayOfMonth INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN profitRatePa REAL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN maturityDateMillis INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN loanNotes TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sender_labels " +
                        "(sender TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL)"
                )
            }
        }

        /**
         * v3 -> v4: tags captured transactions that represent cashback payouts
         * (e.g. Tabby+ "You earned AED X!" messages) so the Rewards tab can
         * surface real earned cashback separately from ordinary credits.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT NOT NULL DEFAULT 'NORMAL'")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mmbank.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Sideloaded test builds get installed out of order; reinstalling
                // an older APK over a newer DB must not hard-crash at launch.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
