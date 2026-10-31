package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xemmy.mmbank.data.local.dao.MerchantAccountDao
import com.xemmy.mmbank.data.local.dao.MerchantKindDao
import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.AssetBalanceDao
import com.xemmy.mmbank.data.local.dao.BillDao
import com.xemmy.mmbank.data.local.dao.CategoryDao
import com.xemmy.mmbank.data.local.dao.ExchangeRateDao
import com.xemmy.mmbank.data.local.dao.FriendDao
import com.xemmy.mmbank.data.local.dao.GroupExpenseDao
import com.xemmy.mmbank.data.local.dao.IgnoredMessagePatternDao
import com.xemmy.mmbank.data.local.dao.MerchantCategoryDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.dao.SavingsGoalDao
import com.xemmy.mmbank.data.local.dao.SenderLabelDao
import com.xemmy.mmbank.data.local.dao.SplitGroupDao
import com.xemmy.mmbank.data.local.dao.SubscriptionDao
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.dao.WalletBalanceDao
import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.MerchantKindMappingEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupMemberEntity
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
        MerchantAccountMappingEntity::class,
        MerchantKindMappingEntity::class,
        SubscriptionEntity::class,
        SavingsGoalEntity::class,
        BillEntity::class,
        FriendEntity::class,
        SplitGroupEntity::class,
        SplitGroupMemberEntity::class,
        GroupExpenseEntity::class,
        ExpenseSplitEntity::class,
        IgnoredMessagePatternEntity::class,
    ],
    version = 29,
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

    abstract fun merchantAccountDao(): MerchantAccountDao
    abstract fun merchantKindDao(): MerchantKindDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun billDao(): BillDao
    abstract fun friendDao(): FriendDao
    abstract fun splitGroupDao(): SplitGroupDao
    abstract fun groupExpenseDao(): GroupExpenseDao
    abstract fun ignoredMessagePatternDao(): IgnoredMessagePatternDao

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

        /**
         * v16 -> v17: High-Detail Transaction Review screen. Captures the
         * parser's raw merchant guess separately from the (possibly later
         * edited) `merchant` column, plus a 0-100 automated-categorization
         * confidence score, so the review queue can show a confidence chip
         * and a raw-vs-current merchant comparison.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN extractedMerchant TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN aiConfidence INTEGER")
            }
        }

        /**
         * Subscription price history: what a subscription used to cost before
         * its charge amount changed, so a price rise can be flagged instead of
         * silently breaking detection. Nullable TEXT, like every other
         * BigDecimal column (see [com.xemmy.mmbank.data.local.Converters]).
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN previousAmount TEXT")
            }
        }

        /**
         * Manually tracked bills (rent, EMI, variable-amount utilities, or a
         * one-off payment) -- see [com.xemmy.mmbank.domain.model.Bill]. Unlike
         * `subscriptions`, never auto-detected, so there's no unique index on
         * name.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bills` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `amount` TEXT NOT NULL,
                        `currencyCode` TEXT NOT NULL,
                        `recurrence` TEXT,
                        `nextDueDateMillis` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v19 -> v20: two-level category hierarchy (e.g. "Dining" under
         * "Food"), so a parent's [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator]
         * status can roll up its children's spend -- see [com.xemmy.mmbank.domain.model.Category.parentCategoryId].
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentCategoryId INTEGER")
            }
        }

        /**
         * v20 -> v21: Splitwise-style shared-expense groups. `friends` (people
         * split with -- names only, not bank-linked), `split_groups`,
         * `split_group_members` (junction; "me" is implicit, never a row),
         * `group_expenses`, and `expense_splits` (one row per participant's
         * share of an expense). See [com.xemmy.mmbank.domain.model.SplitGroup].
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `friends` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `split_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `split_group_members` (
                        `groupId` INTEGER NOT NULL,
                        `friendId` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `friendId`),
                        FOREIGN KEY(`groupId`) REFERENCES `split_groups`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`friendId`) REFERENCES `friends`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_split_group_members_friendId ON split_group_members(friendId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_expenses` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `groupId` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `amount` TEXT NOT NULL,
                        `currencyCode` TEXT NOT NULL,
                        `paidByFriendId` INTEGER,
                        `splitType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`groupId`) REFERENCES `split_groups`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId ON group_expenses(groupId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expense_splits` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `expenseId` INTEGER NOT NULL,
                        `friendId` INTEGER,
                        `owedAmount` TEXT NOT NULL,
                        FOREIGN KEY(`expenseId`) REFERENCES `group_expenses`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_splits_expenseId ON expense_splits(expenseId)")
            }
        }

        /**
         * v21 -> v22: unifies the legacy [com.xemmy.mmbank.domain.model.SpendingCategory]
         * enum onto the dynamic [com.xemmy.mmbank.domain.model.Category] table.
         * Seeds [DEFAULT_CATEGORIES_V22] (the enum values [DEFAULT_CATEGORIES] had
         * no counterpart for), then backfills every transaction's `categoryId`
         * from its already-set `spendingCategory` wherever `categoryId` is still
         * null -- see [com.xemmy.mmbank.domain.model.CategoryMigrationMapping] for
         * the enum-to-name mapping this reuses (also used going forward at capture
         * time by [com.xemmy.mmbank.domain.parser.CaptureCategoryResolver]'s caller).
         *
         * Every seed INSERT is guarded against re-seeding -- `categories.name` has
         * no unique index, unlike the fresh v13 table [DEFAULT_CATEGORIES] seeded.
         * Every backfill UPDATE only touches rows with `categoryId IS NULL`, so a
         * transaction the user already hand-picked a category for is never
         * overwritten -- the same non-destructive posture as the existing
         * `backfillCategoryByMerchant` DAO query. Demo rows are included (not
         * filtered by `isDemo`): they feed the same unified Categories screen.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DEFAULT_CATEGORIES_V22.forEach { (name, icon, colorHex) ->
                    db.execSQL(
                        "INSERT INTO categories (name, icon, colorHex, isCustom) " +
                            "SELECT ?, ?, ?, 0 WHERE NOT EXISTS " +
                            "(SELECT 1 FROM categories WHERE name = ? COLLATE NOCASE)",
                        arrayOf<Any?>(name, icon, colorHex, name),
                    )
                }

                // OTHER and UNCATEGORIZED both target "Uncategorized" -- grouping
                // combines them into one IN(...) statement instead of two.
                val byCategoryName = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME
                    .entries.groupBy({ it.value }, { it.key.name })
                byCategoryName.forEach { (categoryName, enumNames) ->
                    val inClause = enumNames.joinToString(",") { "'$it'" }
                    db.execSQL(
                        """
                        UPDATE transactions SET categoryId = (
                            SELECT id FROM categories WHERE name = ? COLLATE NOCASE LIMIT 1
                        )
                        WHERE categoryId IS NULL AND spendingCategory IN ($inClause)
                          AND EXISTS (SELECT 1 FROM categories WHERE name = ? COLLATE NOCASE)
                        """.trimIndent(),
                        arrayOf<Any?>(categoryName, categoryName),
                    )
                }
            }
        }

        /**
         * v22 -> v23: `merchant_category_mappings`' unique index on
         * `merchantPattern` was case-sensitive (exact byte comparison), while
         * every lookup path treats merchant names case-insensitively --
         * [com.xemmy.mmbank.domain.parser.CategoryMatcher.match]'s
         * `equals(ignoreCase = true)`,
         * [com.xemmy.mmbank.data.local.dao.MerchantCategoryDao.findPending]'s
         * `COLLATE NOCASE`. That mismatch let
         * [com.xemmy.mmbank.data.local.dao.MerchantCategoryDao.upsert]'s
         * REPLACE-on-conflict create duplicate rows for different casings of
         * the same merchant (e.g. "Starbucks" and "STARBUCKS") instead of
         * colliding into one.
         *
         * SQLite can't ALTER a column's collation in place, so this rebuilds
         * the table: for each case-insensitive `merchantPattern` group, keeps
         * exactly one row -- a confirmed mapping (`pending = 0`) over a
         * still-pending suggestion, ties broken by highest `id` (most
         * recently written) -- then recreates the table with
         * `merchantPattern` declared `COLLATE NOCASE` so both the unique
         * index and the plain-equality REPLACE upsert enforce
         * case-insensitivity going forward. See
         * [com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity.merchantPattern]'s
         * matching `@ColumnInfo(collate = NOCASE)` -- Room's `onCreate` for a
         * *fresh* v23+ install builds the table straight from the entity, not
         * this SQL, so without that annotation a brand-new install would
         * still get the old case-sensitive behavior.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE merchant_category_mappings_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        merchantPattern TEXT NOT NULL COLLATE NOCASE,
                        categoryId INTEGER,
                        isExactMatch INTEGER NOT NULL DEFAULT 1,
                        pending INTEGER NOT NULL DEFAULT 0,
                        suggestedName TEXT,
                        suggestedIcon TEXT,
                        suggestedColorHex TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO merchant_category_mappings_new
                        (id, merchantPattern, categoryId, isExactMatch, pending,
                         suggestedName, suggestedIcon, suggestedColorHex)
                    SELECT m.id, m.merchantPattern, m.categoryId, m.isExactMatch, m.pending,
                           m.suggestedName, m.suggestedIcon, m.suggestedColorHex
                    FROM merchant_category_mappings m
                    WHERE m.id = (
                        SELECT m2.id FROM merchant_category_mappings m2
                        WHERE m2.merchantPattern = m.merchantPattern COLLATE NOCASE
                        ORDER BY m2.pending ASC, m2.id DESC
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE merchant_category_mappings")
                db.execSQL("ALTER TABLE merchant_category_mappings_new RENAME TO merchant_category_mappings")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_category_mappings_merchantPattern " +
                        "ON merchant_category_mappings(merchantPattern)"
                )
            }
        }

        /**
         * v23 -> v24: the ignore list. Holds the message templates the user
         * pasted in to stop capturing (promos, OTPs, marketing) -- see
         * [com.xemmy.mmbank.domain.model.IgnoredMessagePattern] and the check
         * [com.xemmy.mmbank.domain.usecase.IngestCapturedMessageUseCase] runs
         * on every captured message. Purely additive: one new table, nothing
         * existing is touched.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ignored_message_patterns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `sample` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_ignored_message_patterns_fingerprint " +
                        "ON ignored_message_patterns(fingerprint)"
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

        /**
         * The [SpendingCategory][com.xemmy.mmbank.domain.model.SpendingCategory]
         * values [DEFAULT_CATEGORIES] had no dynamic counterpart for -- seeded by
         * [MIGRATION_21_22]. Icons reused from the enum's own emoji for visual
         * continuity; colors are new swatches distinct from [DEFAULT_CATEGORIES]'.
         */
        val DEFAULT_CATEGORIES_V22 = listOf(
            Triple("Fuel", "⛽", "#8D6E63"),
            Triple("Entertainment", "🎬", "#7E57C2"),
            Triple("Travel", "✈️", "#5C6BC0"),
            Triple("Transfers", "🔁", "#78909C"),
            Triple("Fees", "🏦", "#EF5350"),
        )

        const val DB_NAME = "mmbank.db"

        /**
         * v24 -> v25: learned merchant -> account rules.
         *
         * Manually assigning a captured transaction to an account used to teach
         * the app nothing, so a merchant whose messages carry no card number
         * stayed unassigned forever -- and an unassigned transaction sits in no
         * balance at all. This table remembers the correction and applies it to
         * later captures (see
         * [com.xemmy.mmbank.domain.parser.CaptureAccountResolver]).
         *
         * Two details that must not drift, because Room builds a FRESH
         * install's table from the entity rather than from this SQL and throws
         * a schema mismatch at startup if the two disagree (the trap
         * MIGRATION_22_23 documents):
         *  - `merchantPattern` is `COLLATE NOCASE`, matching the entity's
         *    `@ColumnInfo(collate = NOCASE)`, so the unique index and the
         *    REPLACE-on-conflict upsert are both case-insensitive.
         *  - the FK child column `accountId` carries its own index, which Room
         *    requires.
         *
         * `ON DELETE CASCADE` means deleting an account cannot leave a rule
         * pointing at a dead id -- the failure `parsing_rules.assignAccountId`
         * had to be fixed by hand because it has no FK.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `merchant_account_mappings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `merchantPattern` TEXT NOT NULL COLLATE NOCASE,
                        `accountId` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_account_mappings_merchantPattern " +
                        "ON merchant_account_mappings(merchantPattern)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_merchant_account_mappings_accountId " +
                        "ON merchant_account_mappings(accountId)"
                )
            }
        }

        /**
         * v25 -> v26: transaction kinds.
         *
         * Until now a row only knew its direction plus one `excludeFromStats`
         * flag, which the app set automatically for three things (Tabby
         * adjustments, reconciled transfer legs, the card-payment credit) and
         * the user set by hand for everything else -- so every total had to
         * guess what a debit or credit meant, and card payments and transfers
         * between the user's own accounts were routinely counted as spending
         * on one side and income on the other.
         *
         * `kind` now carries that meaning (see
         * [com.xemmy.mmbank.domain.model.TransactionKind]). The backfill below
         * mirrors [com.xemmy.mmbank.domain.ledger.TransactionKindDetector.fromLegacy]
         * statement for statement: the automatic exclusions become kinds and
         * lose the flag, while a flag the user set by hand survives. Message
         * wording (ATM, refunds...) can't be read reliably in SQL;
         * [com.xemmy.mmbank.data.ledger.LedgerKindBackfill] re-runs the full
         * detector in Kotlin once after the upgrade.
         *
         * `parsing_rules.assignKind` lets a custom rule pin the kind of what it
         * matches.
         *
         * The statements live in [MigrationSql.MIGRATION_25_26_SQL] so MigrationSqlTest can
         * execute them against real SQLite (likewise for 26->27 and 27->28).
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) = MigrationSql.MIGRATION_25_26_SQL.forEach(db::execSQL)
        }

        /**
         * v26 -> v27: learned merchant -> transaction-kind rules, written when
         * the user re-classifies a transaction with "remember for this
         * merchant" (see [com.xemmy.mmbank.domain.ledger.MerchantKindMatcher]).
         *
         * `merchantPattern` is `COLLATE NOCASE` to match the entity's
         * `@ColumnInfo(collate = NOCASE)` -- Room builds a fresh install's
         * table from the entity and rejects a schema that disagrees.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) = MigrationSql.MIGRATION_26_27_SQL.forEach(db::execSQL)
        }

        /**
         * v27 -> v28: a credit card's limit, and extra card numbers per
         * account (a current account's debit card, supplementary cards), so
         * a message quoting any of them files under the right account.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) = MigrationSql.MIGRATION_27_28_SQL.forEach(db::execSQL)
        }

        /**
         * v28 -> v29: the balance a bank prints in its message
         * ("available balance AED 46,120.85"), kept on the transaction it
         * arrived with (see [com.xemmy.mmbank.domain.parser.BalanceExtractor]).
         * It is the bank's own statement of what the account held, which is
         * what the integrity check reconciles the app's computed balance
         * against. Both columns are nullable: existing rows simply have none.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) = MigrationSql.MIGRATION_28_29_SQL.forEach(db::execSQL)
        }

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
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                    MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                    MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
                )
                // Sideloaded test builds get installed out of order; reinstalling
                // an older APK over a newer DB must not hard-crash at launch.
                .fallbackToDestructiveMigrationOnDowngrade()
                .apply { if (factory != null) openHelperFactory(factory) }
                .build()
    }
}
