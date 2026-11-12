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
 * Executes the v25 -> v33 migrations against real SQLite (sqlite-jdbc) and
 * checks two things Room only finds out on a device:
 *
 *  1. **Schema**: an upgraded database must look exactly like a fresh v33
 *     install, or Room refuses to open it ("Migration didn't properly
 *     handle..."). The fresh install is built from the schema JSON Room's
 *     compiler exports during this same build (`app/schemas/.../33.json`), so
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
            File("schemas/com.xemmy.mmbank.data.local.AppDatabase/33.json"),
            File("app/schemas/com.xemmy.mmbank.data.local.AppDatabase/33.json"),
        ).firstOrNull { it.exists() }
            ?: error("Room schema 33.json not found -- it is exported by kspDebugKotlin (room.schemaLocation)")
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
        "transactions" to listOf("kind", "kindUserSet", "reportedBalance", "reportedBalanceKind"),
        "parsing_rules" to listOf("assignKind"),
        "accounts" to listOf("creditLimit", "cardEndings", "accentColor"),
        "bills" to listOf("dueDayAnchor"),
    )
    private val addedTables = setOf("merchant_kind_mappings", "ignored_keywords", "exchange_rates_history")

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
                val stripped = sql.replace(Regex(",\\s*`$col` [A-Z ]+"), "")
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
        (
            MigrationSql.MIGRATION_25_26_SQL + MigrationSql.MIGRATION_26_27_SQL +
                MigrationSql.MIGRATION_27_28_SQL + MigrationSql.MIGRATION_28_29_SQL +
                MigrationSql.MIGRATION_29_30_SQL + MigrationSql.MIGRATION_30_31_SQL +
                MigrationSql.MIGRATION_31_32_SQL + MigrationSql.MIGRATION_32_33_SQL
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
    fun `an upgraded database matches a fresh v33 install table for table`() {
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
}
