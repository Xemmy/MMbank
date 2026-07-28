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
import com.xemmy.mmbank.data.local.dao.CategoryDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateDao
import com.xemmy.mmbank.data.local.dao.MerchantCategoryDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.dao.SavingsGoalDao
import com.xemmy.mmbank.data.local.dao.SenderLabelDao
import com.xemmy.mmbank.data.local.dao.SubscriptionDao
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.dao.WalletBalanceDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.SubscriptionEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.WalletBalanceEntity

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        ParsingRuleEntity::class,
        AssetBalanceEntity::class,
        WalletBalanceEntity::class,
        ExchangeRateEntity::class,
        SenderLabelEntity::class,
        CategoryEntity::class,
        MerchantCategoryMappingEntity::class,
        SubscriptionEntity::class,
        SavingsGoalEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun parsingRuleDao(): ParsingRuleDao
    abstract fun assetBalanceDao(): AssetBalanceDao
    abstract fun walletBalanceDao(): WalletBalanceDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun senderLabelDao(): SenderLabelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun savingsGoalDao(): SavingsGoalDao

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

        /**
         * v4 -> v5: per-wallet Binance totals (Spot, Funding, Earn, Trading
         * Bots, …) so holdings outside the spot wallet show on the dashboard.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS wallet_balances (" +
                        "walletName TEXT NOT NULL PRIMARY KEY, " +
                        "balanceBtc TEXT NOT NULL, " +
                        "valueUsd TEXT, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v5 -> v6: per-transaction person assignment. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN person TEXT")
            }
        }

        /** v6 -> v7: rules can pin their matches to an account ("default assign"). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parsing_rules ADD COLUMN assignAccountId INTEGER")
            }
        }

        /** v7 -> v8: per-transaction spending category (groceries, dining, fuel, …). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN spendingCategory " +
                        "TEXT NOT NULL DEFAULT 'UNCATEGORIZED'"
                )
            }
        }

        /**
         * v8 -> v9: analytics purity flag on transactions (excludeFromStats:
         * corrections/adjustments stay in the ledger but out of the stats) and
         * the statement-cycle start day on accounts (front-loaded for the
         * statement-window date preset; only Tabby cards use it today, seeded
         * with day 10 = "10th prev month → 9th current month").
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE accounts ADD COLUMN statementCycleDay INTEGER")
                db.execSQL(
                    "UPDATE accounts SET statementCycleDay = 10 " +
                        "WHERE LOWER(name) LIKE '%tabby%' OR LOWER(IFNULL(bankName, '')) LIKE '%tabby%'"
                )
            }
        }

        /**
         * v9 -> v10: "Demo mode" support. A disposable-demo flag on accounts and
         * transactions; real rows default to 0. Demo mode inserts isDemo=1 rows
         * and hides real ones via a runtime filter, then deletes the demo rows
         * when switched off — real data is never touched.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN isDemo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN isDemo INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 -> v11: Demo mode also swaps parsing rules. Same disposable-demo
         * flag on parsing_rules (real rules default to 0), so the Rules screen
         * shows demo rules while demo mode is on without touching real ones.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parsing_rules ADD COLUMN isDemo INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v11 -> v12: transfer reconciliation. A credit-card statement payment
         * posts as two separate messages (a generic debit from the funding
         * account, a credit landing on the card); this links the pair once
         * TransferMatcher recognizes them, so neither leg double-counts in the
         * Dashboard's Income/Spent stats. Null until/unless matched.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN linkedTransactionId INTEGER")
            }
        }

        /**
         * v12 -> v13: dynamic spending categories. A `categories` table
         * (seeded once with the starter set) and a `merchant_category_mappings`
         * table of learned/AI-suggested merchant->category rules, plus the
         * per-transaction category assignment + pending-AI-suggestion fields
         * that ride alongside the existing [SpendingCategory] enum rather than
         * replacing it.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN categoryId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN needsCategoryApproval INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN suggestedCategoryName TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN suggestedCategoryIcon TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN suggestedCategoryColorHex TEXT")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS categories (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "icon TEXT NOT NULL, " +
                        "colorHex TEXT NOT NULL, " +
                        "isCustom INTEGER NOT NULL DEFAULT 0, " +
                        "monthlyCap TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS merchant_category_mappings (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "merchantPattern TEXT NOT NULL, " +
                        "categoryId INTEGER, " +
                        "isExactMatch INTEGER NOT NULL DEFAULT 1, " +
                        "pending INTEGER NOT NULL DEFAULT 0, " +
                        "suggestedName TEXT, suggestedIcon TEXT, suggestedColorHex TEXT)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_category_mappings_merchantPattern " +
                        "ON merchant_category_mappings(merchantPattern)"
                )

                // Starter categories, seeded exactly once (fresh v13 table is
                // always empty at this point, migration or not).
                DEFAULT_CATEGORIES.forEach { (name, icon, colorHex) ->
                    db.execSQL(
                        "INSERT INTO categories (name, icon, colorHex, isCustom) VALUES (?, ?, ?, 0)",
                        arrayOf<Any?>(name, icon, colorHex),
                    )
                }
            }
        }

        /**
         * v13 -> v14: Subscription Radar. One row per merchant with a detected
         * repeating charge (weekly/monthly cadence, within a 5% amount
         * variance -- see [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]),
         * re-detected and upserted on demand rather than kept live-synced.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS subscriptions (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "merchant TEXT NOT NULL, " +
                        "averageAmount TEXT NOT NULL, " +
                        "currencyCode TEXT NOT NULL, " +
                        "cadence TEXT NOT NULL, " +
                        "nextDueDateMillis INTEGER NOT NULL, " +
                        "lastSeenMillis INTEGER NOT NULL, " +
                        "occurrenceCount INTEGER NOT NULL, " +
                        "dismissed INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_subscriptions_merchant ON subscriptions(merchant)"
                )
            }
        }

        /**
         * v14 -> v15: Savings Goals & Debt Payoff Tracker. One row per named
         * goal (Emergency Fund, Vacation, Debt Payoff, …) with progress
         * tracked in `currentAmount`, built up manually or via the Goals
         * screen's "Auto-Allocate Surplus" action.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS savings_goals (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "title TEXT NOT NULL, " +
                        "targetAmount TEXT NOT NULL, " +
                        "currentAmount TEXT NOT NULL, " +
                        "targetDateMillis INTEGER, " +
                        "icon TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "archived INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /**
         * v15 -> v16: per-account Dashboard visibility. Lets an account be
         * hidden from the Dashboard's account carousel (see
         * ManageAccountsBottomSheet) without archiving it -- it still counts
         * toward net worth and can be unhidden any time.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE accounts ADD COLUMN hiddenFromDashboard INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** name, icon, colorHex -- mirrors [com.xemmy.mmbank.domain.model.SpendingCategory]'s built-in buckets. */
        val DEFAULT_CATEGORIES = listOf(
            Triple("Groceries", "🛒", "#4CAF50"),
            Triple("Dining", "🍽️", "#FF7043"),
            Triple("Transport", "🚕", "#29B6F6"),
            Triple("Utilities", "💡", "#FFCA28"),
            Triple("Subscriptions", "🔔", "#AB47BC"),
            Triple("Shopping", "🛍️", "#EC407A"),
            Triple("Health", "🩺", "#26A69A"),
            Triple("Income", "💰", "#66BB6A"),
            Triple("Uncategorized", "🏷️", "#9E9E9E"),
        )

        const val DB_NAME = "mmbank.db"

        /**
         * [factory] swaps in the SQLCipher open helper (see AppContainer);
         * null opens plaintext — the pre-encryption format, kept as the
         * fallback so a failed encryption migration never bricks the app.
         */
        fun build(
            context: Context,
            factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? = null,
        ): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                )
                // Sideloaded test builds get installed out of order; reinstalling
                // an older APK over a newer DB must not hard-crash at launch.
                .fallbackToDestructiveMigrationOnDowngrade()
                .apply { if (factory != null) openHelperFactory(factory) }
                .build()
    }
}
