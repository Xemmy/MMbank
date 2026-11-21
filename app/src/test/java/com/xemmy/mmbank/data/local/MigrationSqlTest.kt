package com.xemmy.mmbank.data.local

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Executes the v25 -> v43 migrations against real SQLite (sqlite-jdbc) and
 * checks two things Room only finds out on a device:
 *
 *  1. **Schema**: an upgraded database must look exactly like a fresh v43
 *     install, or Room refuses to open it ("Migration didn't properly
 *     handle..."). The fresh install is built from the schema JSON Room's
 *     compiler exports during this same build (`app/schemas/.../43.json`), so
 *     the comparison is against what Room itself will validate: every
 *     column's name, type, NOT NULL and primary key, and every index.
 *  2. **Data**: the kind backfill lands each kind of legacy row where
 *     [com.xemmy.mmbank.domain.ledger.TransactionKindDetector.fromLegacy]
 *     says it should, and keeps an exclusion the user set by hand.
 *
 * The v25 starting point is the v29 DDL with the columns/table added since
 * removed -- asserted, so a change to the entities can't silently turn this
 * into a no-op.
 */
class MigrationSqlTest {

    private lateinit var migrated: Connection
    private lateinit var fresh: Connection

    private val schema: JsonObject by lazy {
        val file = listOf(
            File("schemas/com.xemmy.mmbank.data.local.AppDatabase/43.json"),
            File("app/schemas/com.xemmy.mmbank.data.local.AppDatabase/43.json"),
        ).firstOrNull { it.exists() }
            ?: error("Room schema 43.json not found -- it is exported by kspDebugKotlin (room.schemaLocation)")
        Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private data class Table(val name: String, val createSql: String, val indexSql: List<String>)

    private val tables: List<Table> by lazy {
        schema["entities"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val name = o["tableName"]!!.jsonPrimitive.content
            fun sub(sql: String) = sql.replace("\${TABLE_NAME}", name)
            Table(
                name = name,
                createSql = sub(o["createSql"]!!.jsonPrimitive.content),
                indexSql = o["indices"]?.jsonArray.orEmpty().map { sub(it.jsonObject["createSql"]!!.jsonPrimitive.content) },
            )
        }
    }

    /** Columns each table gained in 26-31; removed to rebuild the v25 shape. */
    private val addedColumns = mapOf(
        "transactions" to listOf("kind", "kindUserSet", "reportedBalance", "reportedBalanceKind", "categorySource", "personSource", "refundOfId", "historyOnly", "note"),
        "parsing_rules" to listOf("assignKind"),
        "accounts" to listOf("creditLimit", "cardEndings", "accentColor", "sortOrder", "excludeFromTotals"),
        "categories" to listOf("sortOrder", "flow"),
        "bills" to listOf("dueDayAnchor"),
        "subscriptions" to listOf("isManual"),
    )
    private val addedTables = setOf("merchant_kind_mappings", "ignored_keywords", "exchange_rates_history", "account_changes", "labels", "transaction_labels", "pay_later_plans", "pay_later_installments", "subscription_links")

    @Before
    fun setUp() {
        migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        tables.forEach { t ->
            fresh.exec(t.createSql)
            t.indexSql.forEach { fresh.exec(it) }
        }
        tables.filter { it.name !in addedTables }.forEach { t ->
            var sql = t.createSql
            addedColumns[t.name].orEmpty().forEach { col ->
                // Up to the next comma or closing bracket, so a column with a DEFAULT clause is removed whole.
                val stripped = sql.replace(Regex(",\\s*`$col` [^,)]+"), "")
                assertNotEquals("column $col not found in ${t.name}'s DDL", sql, stripped)
                sql = stripped
            }
            migrated.exec(sql)
            t.indexSql.forEach { migrated.exec(it) }
        }
    }

    @After
    fun tearDown() {
        migrated.close()
        fresh.close()
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun migrate() {
        migrateTo42()
        MigrationSql.MIGRATION_42_43_SQL.forEach { migrated.exec(it) }
    }

    private fun migrateTo42() {
        (
            MigrationSql.MIGRATION_25_26_SQL + MigrationSql.MIGRATION_26_27_SQL +
                MigrationSql.MIGRATION_27_28_SQL + MigrationSql.MIGRATION_28_29_SQL +
                MigrationSql.MIGRATION_29_30_SQL + MigrationSql.MIGRATION_30_31_SQL +
                MigrationSql.MIGRATION_31_32_SQL + MigrationSql.MIGRATION_32_33_SQL +
                MigrationSql.MIGRATION_33_34_SQL + MigrationSql.MIGRATION_34_35_SQL +
                MigrationSql.MIGRATION_35_36_SQL + MigrationSql.MIGRATION_36_37_SQL +
                MigrationSql.MIGRATION_37_38_SQL + MigrationSql.MIGRATION_38_39_SQL +
                MigrationSql.MIGRATION_39_40_SQL + MigrationSql.MIGRATION_40_41_SQL +
                MigrationSql.MIGRATION_41_42_SQL
        )
            .forEach { migrated.exec(it) }
    }

    private fun Connection.columns(table: String): Set<String> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildSet {
                    while (rs.next()) {
                        add("${rs.getString("name")}:${rs.getString("type")}:notnull=${rs.getInt("notnull")}:pk=${rs.getInt("pk")}")
                    }
                }
            }
        }

    private fun Connection.indices(table: String): Set<String> {
        val names = createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name") to rs.getInt("unique")) }
            }
        }.filterNot { it.first.startsWith("sqlite_autoindex") }
        return names.map { (name, unique) ->
            val cols = createStatement().use { st ->
                st.executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
            "$name:unique=$unique:$cols"
        }.toSet()
    }

    private fun Connection.collation(table: String, column: String): Boolean =
        createStatement().use { st ->
            st.executeQuery("SELECT sql FROM sqlite_master WHERE name = '$table'").use { rs ->
                rs.next()
                Regex("`$column` TEXT NOT NULL COLLATE NOCASE").containsMatchIn(rs.getString(1))
            }
        }

    @Test
    fun `an upgraded database matches a fresh v43 install table for table`() {
        migrate()
        val touched = addedColumns.keys + addedTables
        touched.forEach { table ->
            assertEquals("columns of $table", fresh.columns(table), migrated.columns(table))
            assertEquals("indices of $table", fresh.indices(table), migrated.indices(table))
        }
        assertTrue(fresh.collation("merchant_kind_mappings", "merchantPattern"))
        assertTrue(migrated.collation("merchant_kind_mappings", "merchantPattern"))
    }

    @Test
    fun `the kind backfill classifies legacy rows and keeps the user's own exclusions`() {
        migrated.exec("INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) VALUES (1, 'Current', 'CHECKING', 'AED', '0', 0, 0, 0, 0)")
        migrated.exec("INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) VALUES (2, 'Card', 'CREDIT_CARD', 'AED', '0', 0, 0, 0, 0)")
        migrated.exec("INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) VALUES (3, 'Savings', 'SAVINGS', 'AED', '0', 0, 0, 0, 0)")
        fun tx(id: Int, account: Int?, dir: String, merchant: String?, rule: String?, excluded: Int, linked: Int?, category: String = "NORMAL") =
            migrated.exec(
                "INSERT INTO transactions (id, accountId, amount, currencyCode, direction, merchant, source, matchedRuleName, " +
                    "timestamp, createdAt, category, spendingCategory, excludeFromStats, linkedTransactionId, isDemo, needsCategoryApproval) " +
                    "VALUES ($id, ${account ?: "NULL"}, '10', 'AED', '$dir', ${merchant?.let { "'$it'" } ?: "NULL"}, 'SMS', " +
                    "${rule?.let { "'$it'" } ?: "NULL"}, 0, 0, '$category', 'UNCATEGORIZED', $excluded, ${linked ?: "NULL"}, 0, 0)"
            )
        tx(1, 1, "DEBIT", "x", "builtin.en.debit.af.cur-amt", 1, 2)          // bank leg of a card payment
        tx(2, 2, "CREDIT", "y", "builtin.en.transfer.card-payment-credit", 1, 1)
        tx(3, 1, "DEBIT", "a", null, 1, 4)                                     // current -> savings
        tx(4, 3, "CREDIT", "b", null, 1, 3)
        tx(5, 1, "CREDIT", "Balance adjustment", "Balance adjustment", 1, null)
        tx(6, 2, "CREDIT", "Noon", "builtin.en.adjust.decrease", 1, null)
        tx(7, 2, "DEBIT", "Noon", "builtin.en.adjust.increase", 1, null)
        tx(8, 1, "DEBIT", "Gift", null, 1, null)                               // hidden by the user
        tx(9, 2, "CREDIT", "Tabby", "builtin.en.credit.tabby.cashback", 0, null, category = "CASHBACK_EARNED")
        tx(10, 1, "CREDIT", "Salary", null, 0, null)
        tx(11, 1, "DEBIT", "Lulu", null, 0, null)

        migrate()

        val rows = migrated.createStatement().use { st ->
            st.executeQuery("SELECT id, kind, excludeFromStats, kindUserSet FROM transactions ORDER BY id").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getInt(1), Triple(rs.getString(2), rs.getInt(3), rs.getInt(4)))
                }
            }
        }
        val expected = mapOf(
            1 to Triple("CARD_PAYMENT", 0, 0),
            2 to Triple("CARD_PAYMENT", 0, 0),
            3 to Triple("TRANSFER", 0, 0),
            4 to Triple("TRANSFER", 0, 0),
            5 to Triple("ADJUSTMENT", 0, 0),
            6 to Triple("REFUND", 0, 0),
            7 to Triple("SPEND", 0, 0),
            8 to Triple("SPEND", 1, 0),
            9 to Triple("CASHBACK", 0, 0),
            10 to Triple("INCOME", 0, 0),
            11 to Triple("SPEND", 0, 0),
        )
        assertEquals(expected, rows)
    }

    @Test
    fun `the account change log starts empty and takes the rows the app writes`() {
        migrated.exec("INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) VALUES (1, 'Current', 'CHECKING', 'AED', '0', 0, 0, 0, 0)")
        migrate()
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM account_changes").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
        }
        migrated.exec(
            "INSERT INTO account_changes (accountId, accountName, timestamp, type, source, balanceDelta, isDemo) " +
                "VALUES (1, 'Current', 5, 'TRANSACTION_DELETED', 'USER', '-12.40', 0)"
        )
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT id, balanceDelta, field FROM account_changes").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
                assertEquals("-12.40", rs.getString(2))
                assertEquals(null, rs.getString(3))
            }
        }
        // No foreign key: the entry outlives the account.
        migrated.exec("DELETE FROM accounts WHERE id = 1")
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM account_changes").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `existing accounts and rules survive with the new fields unset`() {
        migrated.exec("INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) VALUES (1, 'Card', 'CREDIT_CARD', 'AED', '-50', 0, 0, 0, 0)")
        migrate()
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT name, openingBalance, creditLimit, cardEndings FROM accounts").use { rs ->
                assertTrue(rs.next())
                assertEquals("Card", rs.getString(1))
                assertEquals("-50", rs.getString(2))
                assertEquals(null, rs.getString(3))
                assertEquals(null, rs.getString(4))
            }
        }
    }

    private fun category(id: Int, name: String, custom: Int) =
        migrated.exec("INSERT INTO categories (id, name, icon, colorHex, isCustom) VALUES ($id, '$name', 'x', '#000000', $custom)")

    private fun categoryRows(): List<Triple<String, Int, String>> =
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT name, sortOrder, flow FROM categories ORDER BY sortOrder, id").use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getString(3))) }
            }
        }

    @Test
    fun `categories keep their list order, a category used only for income is flagged, and no seeds are added when one exists`() {
        category(1, "Zebra", 0)
        category(2, "Apple", 0)
        category(3, "Custom A", 1)
        category(4, "Wages", 0)
        migrated.exec(
            "INSERT INTO transactions (id, accountId, amount, currencyCode, direction, merchant, source, matchedRuleName, " +
                "timestamp, createdAt, category, spendingCategory, excludeFromStats, linkedTransactionId, isDemo, " +
                "needsCategoryApproval, categoryId) VALUES (1, NULL, '5000', 'AED', 'CREDIT', 'Employer', 'SMS', NULL, " +
                "0, 0, 'NORMAL', 'UNCATEGORIZED', 0, NULL, 0, 0, 4)"
        )
        migrate()
        // The old ORDER BY isCustom, name: Apple, Wages, Zebra, then the custom one.
        assertEquals(
            listOf(
                Triple("Apple", 0, "EXPENSE"), Triple("Wages", 1, "INCOME"),
                Triple("Zebra", 2, "EXPENSE"), Triple("Custom A", 3, "EXPENSE"),
            ),
            categoryRows(),
        )
    }

    @Test
    fun `a category named Income is flagged, and with no income category at all four starters are added after the rest`() {
        category(1, "Groceries", 0)
        migrate()
        assertEquals(listOf(Triple("Groceries", 0, "EXPENSE")), categoryRows().take(1))
        assertEquals(
            listOf("Salary", "Business", "Gifts received", "Other income"),
            categoryRows().drop(1).map { it.first },
        )
        assertTrue(categoryRows().drop(1).all { it.third == "INCOME" })
        assertEquals(listOf(1, 2, 3, 4), categoryRows().drop(1).map { it.second })
    }

    @Test
    fun `an existing Income category suppresses the starters`() {
        category(1, "Income", 0)
        category(2, "Food", 0)
        migrate()
        assertEquals(2, categoryRows().size)
        assertEquals("INCOME", categoryRows().first { it.first == "Income" }.third)
    }

    @Test
    fun `accounts keep their by-name order and all count in net worth`() {
        fun account(id: Int, name: String) = migrated.exec(
            "INSERT INTO accounts (id, name, type, currencyCode, openingBalance, archived, createdAt, isDemo, hiddenFromDashboard) " +
                "VALUES ($id, '$name', 'CHECKING', 'AED', '0', 0, 0, 0, 0)"
        )
        account(1, "Zed"); account(2, "Alpha"); account(3, "Mid")
        migrate()
        val rows = migrated.createStatement().use { st ->
            st.executeQuery("SELECT name, sortOrder, excludeFromTotals FROM accounts ORDER BY sortOrder").use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getInt(3))) }
            }
        }
        assertEquals(listOf(Triple("Alpha", 0, 0), Triple("Mid", 1, 0), Triple("Zed", 2, 0)), rows)
    }

    @Test
    fun `existing categories and persons are marked as the user's own choice, empty ones as default`() {
        fun tx(id: Int, categoryId: String, person: String) =
            migrated.exec(
                "INSERT INTO transactions (id, accountId, amount, currencyCode, direction, merchant, source, matchedRuleName, " +
                    "timestamp, createdAt, category, spendingCategory, excludeFromStats, linkedTransactionId, isDemo, " +
                    "needsCategoryApproval, categoryId, person) VALUES ($id, NULL, '10', 'AED', 'DEBIT', 'Shop', 'SMS', NULL, " +
                    "0, 0, 'NORMAL', 'UNCATEGORIZED', 0, NULL, 0, 0, $categoryId, $person)"
            )
        tx(1, "5", "'Sam'")
        tx(2, "NULL", "NULL")
        tx(3, "7", "NULL")
        tx(4, "NULL", "'  '")
        tx(5, "NULL", "'Omar'")

        migrate()

        val rows = migrated.createStatement().use { st ->
            st.executeQuery("SELECT id, categorySource, personSource FROM transactions ORDER BY id").use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2) to rs.getString(3)) }
            }
        }
        assertEquals(
            mapOf(
                1 to ("USER" to "USER"),
                2 to ("DEFAULT" to "DEFAULT"),
                3 to ("USER" to "DEFAULT"),
                4 to ("DEFAULT" to "DEFAULT"), // a blank person is no person
                5 to ("DEFAULT" to "USER"),
            ),
            rows,
        )
    }

    @Test
    fun `existing transactions get no refund link`() {
        migrated.exec(
            "INSERT INTO transactions (id, accountId, amount, currencyCode, direction, merchant, source, matchedRuleName, " +
                "timestamp, createdAt, category, spendingCategory, excludeFromStats, linkedTransactionId, isDemo, needsCategoryApproval) " +
                "VALUES (1, NULL, '10', 'AED', 'CREDIT', 'Noon', 'SMS', NULL, 0, 0, 'NORMAL', 'UNCATEGORIZED', 0, NULL, 0, 0)"
        )
        migrate()
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT refundOfId FROM transactions WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                rs.getLong(1)
                assertTrue(rs.wasNull())
            }
        }
    }

    @Test
    fun `existing transactions are not history only`() {
        migrated.exec(
            "INSERT INTO transactions (id, accountId, amount, currencyCode, direction, merchant, source, matchedRuleName, " +
                "timestamp, createdAt, category, spendingCategory, excludeFromStats, linkedTransactionId, isDemo, needsCategoryApproval) " +
                "VALUES (1, NULL, '10', 'AED', 'DEBIT', 'Noon', 'SMS', NULL, 0, 0, 'NORMAL', 'UNCATEGORIZED', 0, NULL, 0, 0)"
        )
        migrate()
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT historyOnly FROM transactions WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun `pay later starts empty and deleting a plan removes its installments`() {
        migrate()
        migrated.exec("PRAGMA foreign_keys = ON")
        assertEquals(0, migrated.count("pay_later_plans"))
        migrated.exec(
            "INSERT INTO pay_later_plans (id, merchant, provider, totalAmount, currencyCode, installmentCount, cadence, createdAt, isDemo) " +
                "VALUES (1, 'Carrefour', 'Tabby', '327.32', 'AED', 4, 'MONTHLY', 0, 0)",
        )
        migrated.exec("INSERT INTO pay_later_installments (planId, number, amount, dueDateMillis) VALUES (1, 1, '81.83', 0)")
        migrated.exec("INSERT INTO pay_later_installments (planId, number, amount, dueDateMillis) VALUES (1, 2, '81.83', 1)")
        assertEquals(2, migrated.count("pay_later_installments"))
        migrated.exec("DELETE FROM pay_later_plans WHERE id = 1")
        assertEquals(0, migrated.count("pay_later_installments"))
    }

    @Test
    fun `existing subscriptions stay detected and links start empty`() {
        // The v25 starting point has no isManual column, so seed through the migrated shape.
        migrated.exec(
            "INSERT INTO subscriptions (id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed) " +
                "VALUES (1, 'Netflix', '39.99', 'AED', 'MONTHLY', 0, 0, 4, 0)",
        )
        migrate()
        assertEquals(1, migrated.count("subscriptions"))
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT isManual FROM subscriptions WHERE id = 1").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
        assertEquals(0, migrated.count("subscription_links"))
        migrated.exec("INSERT INTO subscription_links (transactionId, subscriptionId) VALUES (5, 1)")
        migrated.exec("INSERT OR REPLACE INTO subscription_links (transactionId, subscriptionId) VALUES (5, 2)")
        assertEquals("one link per transaction", 1, migrated.count("subscription_links"))
    }

    @Test
    fun `v43 turns merchant links into id links, drops orphans and allows twin subscriptions`() {
        migrateTo42()
        migrated.exec(
            "INSERT INTO subscriptions (id, merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed, isManual) " +
                "VALUES (1, 'Netflix', '39.99', 'AED', 'MONTHLY', 0, 0, 4, 0, 1), " +
                "(2, ' Spotify ', '19.99', 'AED', 'MONTHLY', 0, 0, 3, 0, 0)",
        )
        migrated.exec(
            "INSERT INTO subscription_links (transactionId, merchant) VALUES (5, 'Netflix'), (6, ' Spotify '), (7, 'Gone')",
        )
        MigrationSql.MIGRATION_42_43_SQL.forEach { migrated.exec(it) }

        val links = migrated.createStatement().use { st ->
            st.executeQuery("SELECT transactionId, subscriptionId FROM subscription_links ORDER BY transactionId").use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1) to rs.getLong(2)) }
            }
        }
        assertEquals("the link to a vanished subscription is dropped", listOf(5L to 1L, 6L to 2L), links)

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT merchantKey, lines FROM subscriptions WHERE id = 2").use { rs ->
                rs.next()
                assertEquals("spotify", rs.getString(1))
                assertEquals(1, rs.getInt(2))
            }
        }
        // The unique merchant index is gone: the same merchant, amount and card can be tracked twice.
        migrated.exec(
            "INSERT INTO subscriptions (merchant, averageAmount, currencyCode, cadence, nextDueDateMillis, lastSeenMillis, occurrenceCount, dismissed, isManual) " +
                "VALUES ('Netflix', '39.99', 'AED', 'MONTHLY', 0, 0, 0, 0, 1)",
        )
        assertEquals(3, migrated.count("subscriptions"))
    }

    private fun Connection.count(table: String): Int =
        createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs -> rs.next(); rs.getInt(1) } }
}
