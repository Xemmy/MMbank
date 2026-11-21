package com.xemmy.mmbank.data.local

/**
 * The raw SQL of the newer migrations, kept free of Room/Android types so
 * MigrationSqlTest can execute exactly these statements against real SQLite
 * on the JVM -- the Migration objects in [AppDatabase] just run them.
 * See [AppDatabase.MIGRATION_25_26] and friends for what each one is for.
 */
object MigrationSql {

    val MIGRATION_25_26_SQL: List<String> = listOf(
        "ALTER TABLE transactions ADD COLUMN kind TEXT NOT NULL DEFAULT 'SPEND'",
        "ALTER TABLE transactions ADD COLUMN kindUserSet INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE parsing_rules ADD COLUMN assignKind TEXT",
        "UPDATE transactions SET kind = 'INCOME' WHERE direction = 'CREDIT'",
        "UPDATE transactions SET kind = 'CASHBACK' WHERE category = 'CASHBACK_EARNED' AND direction = 'CREDIT'",
        // Linked legs and card-payment credits. A leg on a credit card,
        // or whose partner is, is a card payment; anything else linked
        // is a plain transfer between the user's own accounts.
        """
        UPDATE transactions SET excludeFromStats = 0, kind = CASE
            WHEN matchedRuleName LIKE 'builtin.en.transfer.%'
                OR accountId IN (SELECT id FROM accounts WHERE type = 'CREDIT_CARD')
                OR linkedTransactionId IN (
                    SELECT t.id FROM transactions t
                    JOIN accounts a ON a.id = t.accountId
                    WHERE a.type = 'CREDIT_CARD'
                )
            THEN 'CARD_PAYMENT' ELSE 'TRANSFER' END
        WHERE linkedTransactionId IS NOT NULL OR matchedRuleName LIKE 'builtin.en.transfer.%'
        """.trimIndent(),
        "UPDATE transactions SET excludeFromStats = 0, kind = 'ADJUSTMENT' WHERE merchant = 'Balance adjustment'",
        // Tabby partial refunds lower spending now instead of vanishing
        // from it; an "increased by" extra charge is ordinary spending.
        "UPDATE transactions SET excludeFromStats = 0, kind = 'REFUND' " +
            "WHERE matchedRuleName = 'builtin.en.adjust.decrease' AND direction = 'CREDIT'",
        "UPDATE transactions SET excludeFromStats = 0, kind = 'SPEND' " +
            "WHERE matchedRuleName = 'builtin.en.adjust.increase' AND direction = 'DEBIT'",
    )

    val MIGRATION_26_27_SQL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `merchant_kind_mappings` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `merchantPattern` TEXT NOT NULL COLLATE NOCASE,
            `kind` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_kind_mappings_merchantPattern " +
            "ON merchant_kind_mappings(merchantPattern)",
    )

    val MIGRATION_27_28_SQL: List<String> = listOf(
        "ALTER TABLE accounts ADD COLUMN creditLimit TEXT",
        "ALTER TABLE accounts ADD COLUMN cardEndings TEXT",
    )

    val MIGRATION_28_29_SQL: List<String> = listOf(
        "ALTER TABLE transactions ADD COLUMN reportedBalance TEXT",
        "ALTER TABLE transactions ADD COLUMN reportedBalanceKind TEXT",
    )

    /**
     * v36: the account change log. Purely additive: one new table and its index,
     * no existing data touched. Column order and types mirror what Room exports
     * for AccountChangeEntity (MigrationSqlTest compares them).
     */
    val MIGRATION_35_36_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `account_changes` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`accountId` INTEGER NOT NULL, " +
            "`accountName` TEXT NOT NULL, " +
            "`timestamp` INTEGER NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, " +
            "`field` TEXT, " +
            "`oldValue` TEXT, " +
            "`newValue` TEXT, " +
            "`balanceDelta` TEXT, " +
            "`currencyCode` TEXT, " +
            "`transactionId` INTEGER, " +
            "`reason` TEXT, " +
            "`note` TEXT, " +
            "`isDemo` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_account_changes_accountId_timestamp` " +
            "ON `account_changes` (`accountId`, `timestamp`)",
    )

    /**
     * v41: Pay Later plans and their installments. Purely additive: two new tables and an
     * index. Column order and types mirror what Room exports for PayLaterPlanEntity /
     * PayLaterInstallmentEntity (MigrationSqlTest compares them).
     */
    val MIGRATION_40_41_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `pay_later_plans` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`merchant` TEXT NOT NULL, " +
            "`provider` TEXT NOT NULL, " +
            "`totalAmount` TEXT NOT NULL, " +
            "`currencyCode` TEXT NOT NULL, " +
            "`installmentCount` INTEGER NOT NULL, " +
            "`cadence` TEXT NOT NULL, " +
            "`payFromAccountId` INTEGER, " +
            "`payToAccountId` INTEGER, " +
            "`sourceTransactionId` INTEGER, " +
            "`categoryId` INTEGER, " +
            "`note` TEXT, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`isDemo` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `pay_later_installments` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`planId` INTEGER NOT NULL, " +
            "`number` INTEGER NOT NULL, " +
            "`amount` TEXT NOT NULL, " +
            "`dueDateMillis` INTEGER NOT NULL, " +
            "`paidAtMillis` INTEGER, " +
            "`paymentTransactionId` INTEGER, " +
            "FOREIGN KEY(`planId`) REFERENCES `pay_later_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS `index_pay_later_installments_planId` ON `pay_later_installments` (`planId`)",
    )

    /** v45 (from v44): a Pay Later plan whose captured purchase was itself one installment. */
    val MIGRATION_44_45_SQL: List<String> = listOf(
        "ALTER TABLE `pay_later_plans` ADD COLUMN `sourceIsInstallment` INTEGER NOT NULL DEFAULT 0",
    )

    /**
     * v44 (from v43): `confidence` for detected subscriptions. 1.0 for every existing row, so
     * nothing already shown moves into the "Possible" list; only new low-confidence detections do.
     */
    val MIGRATION_43_44_SQL: List<String> = listOf(
        "ALTER TABLE `subscriptions` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1.0",
    )

    /**
     * v43 (from v42): subscriptions keyed by id. The unique merchant index goes (several
     * subscriptions at one merchant, even identical ones, are allowed); `merchantKey` is backfilled
     * as the lower-cased trimmed merchant and refined by the next rescan; `lines` counts parallel
     * identical charges and starts at 1. `subscription_links` is rebuilt to point at the
     * subscription's id: links are carried over through a join on the old merchant column and a
     * link whose subscription no longer exists is dropped.
     */
    val MIGRATION_42_43_SQL: List<String> = listOf(
        "DROP INDEX IF EXISTS `index_subscriptions_merchant`",
        "CREATE INDEX IF NOT EXISTS `index_subscriptions_merchant` ON `subscriptions` (`merchant`)",
        "ALTER TABLE `subscriptions` ADD COLUMN `merchantKey` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `subscriptions` ADD COLUMN `lines` INTEGER NOT NULL DEFAULT 1",
        "UPDATE `subscriptions` SET `merchantKey` = LOWER(TRIM(`merchant`))",
        "CREATE TABLE IF NOT EXISTS `subscription_links_new` (" +
            "`transactionId` INTEGER PRIMARY KEY NOT NULL, " +
            "`subscriptionId` INTEGER NOT NULL)",
        "INSERT OR REPLACE INTO `subscription_links_new` (`transactionId`, `subscriptionId`) " +
            "SELECT l.`transactionId`, s.`id` FROM `subscription_links` l " +
            "INNER JOIN `subscriptions` s ON s.`merchant` = l.`merchant`",
        "DROP TABLE `subscription_links`",
        "ALTER TABLE `subscription_links_new` RENAME TO `subscription_links`",
        "CREATE INDEX IF NOT EXISTS `index_subscription_links_subscriptionId` ON `subscription_links` (`subscriptionId`)",
    )

    /**
     * v42 (from v41): hand-added subscriptions and manual transaction links. `isManual` defaults
     * to 0 so every detected row stays detected; the link table starts empty.
     */
    val MIGRATION_41_42_SQL: List<String> = listOf(
        "ALTER TABLE `subscriptions` ADD COLUMN `isManual` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `subscription_links` (" +
            "`transactionId` INTEGER PRIMARY KEY NOT NULL, " +
            "`merchant` TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_subscription_links_merchant` ON `subscription_links` (`merchant`)",
    )

    /**
     * v40: account order and "count in net worth". `sortOrder` starts as each account's current
     * position (by name, as the lists were ordered), so nothing moves; `excludeFromTotals` starts
     * false for every account. Purely additive; defaults match the entity's `@ColumnInfo`.
     */
    val MIGRATION_39_40_SQL: List<String> = listOf(
        "ALTER TABLE `accounts` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `accounts` ADD COLUMN `excludeFromTotals` INTEGER NOT NULL DEFAULT 0",
        "UPDATE `accounts` SET `sortOrder` = (SELECT COUNT(*) FROM `accounts` AS a2 WHERE " +
            "a2.`name` < `accounts`.`name` OR (a2.`name` = `accounts`.`name` AND a2.`id` < `accounts`.`id`))",
    )

    /**
     * v39: category order and flow. `sortOrder` starts as each category's current position in the
     * list (custom last, then by name, exactly the old ORDER BY) so nothing moves. `flow` marks
     * "Income" and any category used only by Received rows as INCOME; when that leaves no income
     * category at all, four starter ones are added. Purely additive. The defaults match the
     * entity's `@ColumnInfo(defaultValue)`.
     */
    val MIGRATION_38_39_SQL: List<String> = listOf(
        "ALTER TABLE `categories` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `categories` ADD COLUMN `flow` TEXT NOT NULL DEFAULT 'EXPENSE'",
        "UPDATE `categories` SET `sortOrder` = (SELECT COUNT(*) FROM `categories` AS c2 WHERE " +
            "c2.`isCustom` < `categories`.`isCustom` OR (c2.`isCustom` = `categories`.`isCustom` AND " +
            "(c2.`name` < `categories`.`name` OR (c2.`name` = `categories`.`name` AND c2.`id` < `categories`.`id`))))",
        "UPDATE `categories` SET `flow` = 'INCOME' WHERE lower(`name`) = 'income'",
        "UPDATE `categories` SET `flow` = 'INCOME' WHERE `id` IN (" +
            "SELECT `categoryId` FROM `transactions` WHERE `categoryId` IS NOT NULL " +
            "GROUP BY `categoryId` HAVING SUM(CASE WHEN `kind` = 'INCOME' THEN 0 ELSE 1 END) = 0)",
        "INSERT INTO `categories` (`name`, `icon`, `colorHex`, `isCustom`, `sortOrder`, `flow`) " +
            "SELECT `name`, `icon`, `colorHex`, 0, (SELECT COUNT(*) FROM `categories`) + `pos`, 'INCOME' FROM (" +
            "SELECT 'Salary' AS `name`, '💼' AS `icon`, '#4CAF50' AS `colorHex`, 0 AS `pos` " +
            "UNION ALL SELECT 'Business', '🏢', '#29B6F6', 1 " +
            "UNION ALL SELECT 'Gifts received', '🎁', '#EC407A', 2 " +
            "UNION ALL SELECT 'Other income', '💰', '#FFCA28', 3) " +
            "WHERE NOT EXISTS (SELECT 1 FROM `categories` WHERE `flow` = 'INCOME')",
    )

    /**
     * v38: a free-text note on transactions, plus labels and the transaction <-> label junction
     * (see Label). Purely additive. Column order and index names mirror what Room exports for
     * LabelEntity / TransactionLabelEntity (MigrationSqlTest compares them).
     */
    val MIGRATION_37_38_SQL: List<String> = listOf(
        "ALTER TABLE `transactions` ADD COLUMN `note` TEXT",
        "CREATE TABLE IF NOT EXISTS `labels` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`colorHex` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_labels_name` ON `labels` (`name`)",
        "CREATE TABLE IF NOT EXISTS `transaction_labels` (" +
            "`transactionId` INTEGER NOT NULL, " +
            "`labelId` INTEGER NOT NULL, " +
            "PRIMARY KEY(`transactionId`, `labelId`), " +
            "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`labelId`) REFERENCES `labels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS `index_transaction_labels_labelId` ON `transaction_labels` (`labelId`)",
    )

    /** v37: "history only" on transactions (see Transaction.historyOnly). Purely additive; every existing row is false. */
    val MIGRATION_36_37_SQL: List<String> = listOf(
        "ALTER TABLE `transactions` ADD COLUMN `historyOnly` INTEGER NOT NULL DEFAULT 0",
    )

    /** v35: the purchase a refund gave money back for. Purely additive: one nullable column. */
    val MIGRATION_34_35_SQL: List<String> = listOf(
        "ALTER TABLE `transactions` ADD COLUMN `refundOfId` INTEGER",
    )

    /**
     * v34: who decided each transaction's category and person (see
     * AssignmentSource). Existing rows can't say, so every one that has a
     * category or a person is marked USER: the app never re-assigns history
     * on its own, which is the same behaviour as before this column existed.
     */
    val MIGRATION_33_34_SQL: List<String> = listOf(
        "ALTER TABLE `transactions` ADD COLUMN `categorySource` TEXT NOT NULL DEFAULT 'DEFAULT'",
        "ALTER TABLE `transactions` ADD COLUMN `personSource` TEXT NOT NULL DEFAULT 'DEFAULT'",
        "UPDATE `transactions` SET `categorySource` = 'USER' WHERE `categoryId` IS NOT NULL",
        "UPDATE `transactions` SET `personSource` = 'USER' WHERE `person` IS NOT NULL AND TRIM(`person`) != ''",
    )

    /** v33: daily forex-rate history. Purely additive: one new table, no existing data touched. */
    val MIGRATION_32_33_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `exchange_rates_history` (" +
            "`baseCode` TEXT NOT NULL, `quoteCode` TEXT NOT NULL, `day` TEXT NOT NULL, `rate` TEXT NOT NULL, " +
            "PRIMARY KEY(`baseCode`, `quoteCode`, `day`))",
    )

    /** v32: optional per-account accent colour (ARGB int). Purely additive: one nullable column. */
    val MIGRATION_31_32_SQL: List<String> = listOf(
        "ALTER TABLE `accounts` ADD COLUMN `accentColor` INTEGER",
    )

    /** v31: bills remember the day of month they were set for. Purely additive: one nullable column. */
    val MIGRATION_30_31_SQL: List<String> = listOf(
        "ALTER TABLE `bills` ADD COLUMN `dueDayAnchor` INTEGER",
    )

    /** v29 -> v30: the keyword ignore list. Purely additive: one new table. */
    val MIGRATION_29_30_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `ignored_keywords` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`keyword` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_ignored_keywords_keyword ON ignored_keywords(keyword)",
    )
}
