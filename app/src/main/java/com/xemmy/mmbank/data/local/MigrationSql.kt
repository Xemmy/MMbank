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
}
