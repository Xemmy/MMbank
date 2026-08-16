package com.xemmy.mmbank.domain.demo

import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Builds a realistic, disposable batch of sample accounts (incl. a credit card
 * and a loan), plus transactions spanning several months, spending categories
 * and both directions. Every row is tagged `isDemo = true` so the "Demo mode"
 * toggle can show them in place of the real ledger and delete them cleanly.
 *
 * Pure Kotlin: the clock and the RNG are injected, so output is deterministic
 * under a seeded [Random] and the whole thing is unit-testable on the JVM.
 * Accounts carry temporary [SeedAccount.key]s; once inserted (and Room assigns
 * real ids) [Batch.transactions] resolves each transaction to its account id.
 */
object DemoDataGenerator {

    const val KEY_CHECKING = 1
    const val KEY_SAVINGS = 2
    const val KEY_CARD = 3
    const val KEY_LOAN = 4
    const val KEY_CRYPTO = 5

    /** Sample people demo transactions are shared between while Demo mode is on. */
    val DEMO_PEOPLE = listOf("Alex Rivera", "Sam Carter", "Jordan Blake")

    /** An account to insert, paired with the temp key its transactions reference. */
    data class SeedAccount(val key: Int, val entity: AccountEntity)

    class Batch internal constructor(
        val accounts: List<SeedAccount>,
        val rules: List<ParsingRuleEntity>,
        private val pending: List<PendingTransaction>,
    ) {
        /**
         * Resolves each demo transaction's temp account key to the real
         * inserted row id, and its [SpendingCategory] to a dynamic category id
         * (see [com.xemmy.mmbank.domain.model.CategoryMigrationMapping]) when
         * the caller supplies one -- so demo mode showcases the unified
         * Categories screen the same way real captured transactions do.
         */
        fun transactions(idByKey: Map<Int, Long>, categoryIdByEnum: Map<SpendingCategory, Long> = emptyMap()): List<TransactionEntity> =
            pending.map { it.toEntity(idByKey.getValue(it.accountKey), categoryIdByEnum[it.spendingCategory]) }
    }

    internal data class PendingTransaction(
        val accountKey: Int,
        val amount: BigDecimal,
        val currencyCode: String,
        val direction: TransactionDirection,
        val merchant: String,
        val spendingCategory: SpendingCategory,
        val category: TransactionCategory,
        val timestampMillis: Long,
        val createdAt: Long,
        val person: String? = null,
    ) {
        fun toEntity(accountId: Long, categoryId: Long? = null) = TransactionEntity(
            accountId = accountId,
            amount = amount,
            currencyCode = currencyCode,
            direction = direction,
            merchant = merchant,
            source = TransactionSource.MANUAL,
            timestamp = timestampMillis,
            createdAt = createdAt,
            category = category,
            spendingCategory = spendingCategory,
            categoryId = categoryId,
            person = person,
            isDemo = true,
        )
    }

    fun generate(
        currency: String = "USD",
        random: Random = Random,
        now: Long = System.currentTimeMillis(),
    ): Batch {
        val accounts = listOf(
            SeedAccount(
                KEY_CHECKING,
                AccountEntity(
                    name = "Everyday Checking",
                    type = AccountType.CHECKING,
                    currencyCode = currency,
                    openingBalance = BigDecimal("2500.00"),
                    createdAt = now,
                    bankName = "Demo Bank",
                    accountNumber = "1002",
                    isDemo = true,
                ),
            ),
            SeedAccount(
                KEY_SAVINGS,
                AccountEntity(
                    name = "Rainy Day Savings",
                    type = AccountType.SAVINGS,
                    currencyCode = currency,
                    openingBalance = BigDecimal("12000.00"),
                    createdAt = now,
                    bankName = "Demo Bank",
                    accountNumber = "3007",
                    isDemo = true,
                ),
            ),
            SeedAccount(
                KEY_CARD,
                AccountEntity(
                    name = "Platinum Rewards Card",
                    type = AccountType.CREDIT_CARD,
                    currencyCode = currency,
                    openingBalance = BigDecimal.ZERO,
                    createdAt = now,
                    bankName = "Demo Bank",
                    accountNumber = "4821",
                    outstandingBalance = BigDecimal("1420.75"),
                    nextInstallmentAmount = BigDecimal("150.00"),
                    dueDayOfMonth = 18,
                    statementCycleDay = 10,
                    isDemo = true,
                ),
            ),
            SeedAccount(
                KEY_LOAN,
                AccountEntity(
                    name = "Auto Loan",
                    type = AccountType.LOAN,
                    currencyCode = currency,
                    openingBalance = BigDecimal.ZERO,
                    createdAt = now,
                    bankName = "Demo Finance",
                    outstandingBalance = BigDecimal("18450.00"),
                    nextInstallmentAmount = BigDecimal("512.30"),
                    dueDayOfMonth = 5,
                    profitRatePa = 5.49,
                    maturityDateMillis = now + 900L * DAY_MS,
                    loanNotes = "48-month term, no early-settlement fee",
                    isDemo = true,
                ),
            ),
            SeedAccount(
                KEY_CRYPTO,
                AccountEntity(
                    name = "Crypto Spot",
                    type = AccountType.CRYPTO_SPOT,
                    currencyCode = currency,
                    openingBalance = BigDecimal("850.00"),
                    createdAt = now,
                    isDemo = true,
                ),
            ),
        )

        val txns = mutableListOf<PendingTransaction>()

        // Monthly salary into checking for the last three months (CREDIT / income).
        for (monthsAgo in 1..3) {
            txns += pending(
                random, now, KEY_CHECKING, TransactionDirection.CREDIT, "Acme Corp Payroll",
                SpendingCategory.INCOME, amount(random, 4200.0, 4200.0),
                daysAgo = monthsAgo * 30,
            )
        }

        // A spread of card spends across categories over the last ~two months.
        val cardSpends = listOf(
            Spend("Whole Foods Market", SpendingCategory.GROCERIES, 35.0, 140.0),
            Spend("Trader Joe's", SpendingCategory.GROCERIES, 22.0, 95.0),
            Spend("Blue Bottle Coffee", SpendingCategory.DINING, 5.0, 24.0),
            Spend("Shake Shack", SpendingCategory.DINING, 14.0, 58.0),
            Spend("Uber", SpendingCategory.TRANSPORT, 8.0, 42.0),
            Spend("Shell", SpendingCategory.FUEL, 30.0, 78.0),
            Spend("Amazon", SpendingCategory.SHOPPING, 12.0, 220.0),
            Spend("IKEA", SpendingCategory.SHOPPING, 45.0, 310.0),
            Spend("Netflix", SpendingCategory.ENTERTAINMENT, 15.99, 15.99),
            Spend("Spotify", SpendingCategory.ENTERTAINMENT, 11.99, 11.99),
            Spend("Skyline Airlines", SpendingCategory.TRAVEL, 180.0, 640.0),
        )
        cardSpends.forEachIndexed { i, s ->
            txns += pending(
                random, now, KEY_CARD, TransactionDirection.DEBIT, s.merchant,
                s.category, amount(random, s.min, s.max), daysAgo = 3 + i * 5,
            )
        }

        // Everyday-checking bills.
        txns += pending(
            random, now, KEY_CHECKING, TransactionDirection.DEBIT, "City Power & Light",
            SpendingCategory.UTILITIES, amount(random, 60.0, 180.0), daysAgo = 12,
        )
        txns += pending(
            random, now, KEY_CHECKING, TransactionDirection.DEBIT, "Fiber Internet",
            SpendingCategory.UTILITIES, amount(random, 45.0, 90.0), daysAgo = 20,
        )
        txns += pending(
            random, now, KEY_CHECKING, TransactionDirection.DEBIT, "CVS Pharmacy",
            SpendingCategory.HEALTH, amount(random, 8.0, 65.0), daysAgo = 27,
        )

        // A transfer into savings (CREDIT) and a small crypto buy (DEBIT).
        txns += pending(
            random, now, KEY_SAVINGS, TransactionDirection.CREDIT, "Transfer to Savings",
            SpendingCategory.TRANSFERS, amount(random, 300.0, 800.0), daysAgo = 8,
        )
        txns += pending(
            random, now, KEY_CRYPTO, TransactionDirection.DEBIT, "BTC Buy",
            SpendingCategory.OTHER, amount(random, 50.0, 250.0), daysAgo = 15,
        )

        // Cashback earned on the card (CREDIT, tagged so Rewards has something to show).
        for (monthsAgo in 1..2) {
            txns += pending(
                random, now, KEY_CARD, TransactionDirection.CREDIT, "Rewards Cashback",
                SpendingCategory.INCOME, amount(random, 6.0, 34.0),
                daysAgo = monthsAgo * 30 + 2, category = TransactionCategory.CASHBACK_EARNED,
            )
        }

        // Share the debit spends among the demo people; leave income/transfers
        // unassigned so the people list looks like a real mix.
        var personIdx = 0
        val withMeta = txns.map { t ->
            val person = if (t.direction == TransactionDirection.DEBIT) {
                DEMO_PEOPLE[personIdx++ % DEMO_PEOPLE.size]
            } else {
                null
            }
            t.copy(currencyCode = currency, person = person)
        }

        return Batch(
            accounts = accounts,
            rules = demoRules(now),
            pending = withMeta.sortedByDescending { it.timestampMillis },
        )
    }

    /** A few illustrative parsing rules shown on the Rules screen during a demo. */
    private fun demoRules(now: Long): List<ParsingRuleEntity> = listOf(
        ParsingRuleEntity(
            name = "Demo Bank card spend",
            pattern = "You spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) " +
                "at (?<merchant>[^.]+?)\\.",
            senderPattern = "demobank",
            direction = TransactionDirection.DEBIT,
            priority = 110,
            sampleText = "You spent USD 42.90 at BLUE BOTTLE COFFEE. Card ending 4821.",
            createdAt = now,
            isDemo = true,
        ),
        ParsingRuleEntity(
            name = "Demo Bank salary credit",
            pattern = "Salary of (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) credited",
            senderPattern = "demobank",
            direction = TransactionDirection.CREDIT,
            priority = 110,
            sampleText = "Salary of USD 4200.00 credited to your Everyday Checking account.",
            createdAt = now,
            isDemo = true,
        ),
        ParsingRuleEntity(
            name = "Demo Finance loan installment",
            pattern = "Installment of (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) received",
            senderPattern = "demofinance",
            direction = TransactionDirection.DEBIT,
            priority = 100,
            sampleText = "Installment of USD 512.30 received for your Auto Loan.",
            createdAt = now,
            isDemo = true,
        ),
    )

    private data class Spend(
        val merchant: String,
        val category: SpendingCategory,
        val min: Double,
        val max: Double,
    )

    private fun pending(
        random: Random,
        now: Long,
        accountKey: Int,
        direction: TransactionDirection,
        merchant: String,
        spendingCategory: SpendingCategory,
        amount: BigDecimal,
        daysAgo: Int,
        category: TransactionCategory = TransactionCategory.NORMAL,
    ): PendingTransaction {
        // Scatter within the target day so rows don't share an exact timestamp.
        val ts = now - daysAgo * DAY_MS - (random.nextDouble() * DAY_MS).toLong()
        return PendingTransaction(
            accountKey = accountKey,
            amount = amount,
            currencyCode = "", // filled in by generate() once the batch currency is known
            direction = direction,
            merchant = merchant,
            spendingCategory = spendingCategory,
            category = category,
            timestampMillis = ts,
            createdAt = now,
        )
    }

    private fun amount(random: Random, min: Double, max: Double): BigDecimal =
        BigDecimal(min + random.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP)

    private const val DAY_MS = 86_400_000L
}
