package com.xemmy.mmbank.ui.screens.search

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.time.Instant
import java.time.ZoneId

/** Somewhere a search result can take the user. Pure data; the screen maps it to navigation. */
sealed interface SearchTarget {
    data class Route(val route: String) : SearchTarget
    data class Merchant(val merchant: String) : SearchTarget
    data class Rule(val ruleId: Long) : SearchTarget
}

enum class SearchKind(val label: String) {
    DESTINATION("Go to"),
    MERCHANT("Merchants"),
    TRANSACTION("Transactions"),
    ACCOUNT("Accounts"),
    RULE("Parsing rules"),
}

data class SearchResult(
    val kind: SearchKind,
    val title: String,
    val subtitle: String,
    val target: SearchTarget,
    val score: Int,
)

/** A screen or setting the user can jump to by name. [keywords] are extra words that should find it. */
data class SearchDestination(val title: String, val keywords: String, val route: String)

/**
 * One search across screens and settings, merchants, transactions, accounts
 * and parsing rules (backlog B.5 #2). Pure, so the whole ranking is unit
 * tested.
 *
 * Every word of the query must appear (in any order, as a prefix or inside a
 * word) for a row to match. Ranking: exact title > title starts with the
 * query > every word starts a word of the title > plain contains. Results are
 * ordered by score, then by [SearchKind] (screens first), and each kind is
 * capped so one chatty merchant can't push everything else off the list.
 */
object GlobalSearch {

    const val MIN_QUERY_CHARS = 2
    const val PER_KIND_LIMIT = 5
    const val TOTAL_LIMIT = 30

    fun search(
        query: String,
        transactions: List<Transaction>,
        accounts: List<Account>,
        rules: List<ParsingRule>,
        destinations: List<SearchDestination>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SearchResult> {
        val q = normalize(query)
        if (q.length < MIN_QUERY_CHARS) return emptyList()
        val words = q.split(' ')

        val all = buildList {
            addAll(destinationResults(q, words, destinations))
            addAll(merchantResults(q, words, transactions))
            addAll(transactionResults(q, words, transactions, zone))
            addAll(accountResults(q, words, accounts))
            addAll(ruleResults(q, words, rules))
        }
        return all
            .groupBy { it.kind }
            .flatMap { (_, rows) -> rows.sortedByDescending { it.score }.take(PER_KIND_LIMIT) }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.kind.ordinal })
            .take(TOTAL_LIMIT)
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    /** 0 = no match; otherwise higher is better. */
    internal fun score(text: String, query: String, words: List<String>): Int {
        val t = normalize(text)
        if (t.isEmpty()) return 0
        if (t == query) return 100
        if (words.any { !t.contains(it) }) return 0
        if (t.startsWith(query)) return 80
        val titleWords = t.split(' ', '-', '.', '/', '&')
        if (words.all { w -> titleWords.any { it.startsWith(w) } }) return 60
        return 40
    }

    private fun destinationResults(q: String, words: List<String>, destinations: List<SearchDestination>) =
        destinations.mapNotNull { d ->
            val s = maxOf(score(d.title, q, words), score(d.keywords, q, words) / 2)
            if (s == 0) null else SearchResult(SearchKind.DESTINATION, d.title, "", SearchTarget.Route(d.route), s)
        }

    private fun merchantResults(q: String, words: List<String>, transactions: List<Transaction>): List<SearchResult> =
        transactions
            .filter { !it.merchant.isNullOrBlank() }
            .groupBy { MerchantNormalizer.key(it.merchant).ifEmpty { it.merchant!!.trim().lowercase() } }
            .values
            .mapNotNull { rows ->
                val latest = rows.maxByOrNull { it.timestampMillis }!!
                val name = latest.merchant!!.trim()
                val s = score(name, q, words)
                if (s == 0) {
                    null
                } else {
                    SearchResult(
                        SearchKind.MERCHANT, name,
                        if (rows.size == 1) "1 transaction" else "${rows.size} transactions",
                        SearchTarget.Merchant(name), s,
                    )
                }
            }

    private fun transactionResults(
        q: String,
        words: List<String>,
        transactions: List<Transaction>,
        zone: ZoneId,
    ): List<SearchResult> {
        val queryAmount = if (words.size == 1) q.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } else null
        return transactions
            .sortedByDescending { it.timestampMillis }
            .mapNotNull { tx ->
                val merchantScore = score(tx.merchant.orEmpty(), q, words)
                // A typed number matches an amount exactly ("120.5" finds AED 120.50), never as a loose substring.
                val amountScore = if (queryAmount != null && tx.amount.compareTo(queryAmount) == 0) 70 else 0
                val s = maxOf(merchantScore, amountScore)
                if (s == 0) {
                    null
                } else {
                    val date = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
                    val sign = if (tx.direction == TransactionDirection.CREDIT) "+" else "-"
                    SearchResult(
                        SearchKind.TRANSACTION,
                        tx.merchant?.takeIf { it.isNotBlank() } ?: "(no merchant)",
                        "$sign${tx.amount.toPlainString()} ${tx.currencyCode} · $date",
                        tx.merchant?.takeIf { it.isNotBlank() }?.let { SearchTarget.Merchant(it.trim()) }
                            ?: SearchTarget.Route("transactions"),
                        s,
                    )
                }
            }
    }

    private fun accountResults(q: String, words: List<String>, accounts: List<Account>): List<SearchResult> =
        accounts.mapNotNull { a ->
            val s = maxOf(
                score(a.name, q, words),
                score(a.bankName.orEmpty(), q, words),
                // The visible ending: "1234" finds the account whose number ends in it.
                if (words.size == 1 && a.accountNumber?.takeLast(4)?.let { it == q } == true) 70 else 0,
            )
            if (s == 0) {
                null
            } else {
                val detail = listOfNotNull(a.bankName?.takeIf { it.isNotBlank() }, a.currencyCode).joinToString(" · ")
                SearchResult(SearchKind.ACCOUNT, a.name, detail, SearchTarget.Route("accounts"), s)
            }
        }

    private fun ruleResults(q: String, words: List<String>, rules: List<ParsingRule>): List<SearchResult> =
        rules.mapNotNull { r ->
            val s = score(r.name, q, words)
            if (s == 0) null else SearchResult(
                SearchKind.RULE, r.name,
                if (r.enabled) "Parsing rule" else "Parsing rule · off",
                SearchTarget.Rule(r.id), s,
            )
        }
}
