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
