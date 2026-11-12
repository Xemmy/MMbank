package com.xemmy.mmbank.domain.ledger

/**
 * Who a freshly captured transaction belongs to, learned from the user's own
 * past corrections (backlog Part C.2): every capture used to get the single
 * default person, however many times the user had moved that merchant's rows
 * to someone else.
 *
 * Rule, deliberately tiny and explainable: when the **last two** transactions
 * at this merchant went to the same person, so does the new one. One stray
 * correction changes nothing; two in a row is a pattern; and two defaults in a
 * row after that hand the merchant back, so the learning can't stick forever
 * on a mistake. With no pattern the default person applies, as before.
 *
 * Pure -- the caller supplies the two most recent persons (newest first).
 */
object PersonLearner {

    /** How many recent rows the pattern is read from. */
    const val WINDOW = 2

    fun pick(recentPersonsNewestFirst: List<String>, defaultPerson: String?): String? {
        val recent = recentPersonsNewestFirst.map { it.trim() }.filter { it.isNotEmpty() }.take(WINDOW)
        if (recent.size == WINDOW && recent.all { it.equals(recent.first(), ignoreCase = true) }) {
            return recent.first()
        }
        return defaultPerson?.takeIf { it.isNotBlank() }
    }
}
