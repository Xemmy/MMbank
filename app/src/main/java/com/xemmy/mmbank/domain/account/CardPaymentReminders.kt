package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator.CardStatus
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator.Status
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which credit cards to remind about today, and keeping each due date from
 * being announced twice -- the card counterpart to
 * [com.xemmy.mmbank.domain.intelligence.BillReminders]. Pure.
 *
 * A card reminds when something is still due and the due date is within
 * [WINDOW_DAYS] (or already passed). A reminder is keyed on the card *and*
 * the due date, so the next month's due date re-arms by itself; a card paid
 * in full never reminds.
 */
object CardPaymentReminders {

    const val WINDOW_DAYS = 3L

    data class CardReminder(val accountId: Long, val accountName: String, val currencyCode: String, val status: CardStatus)

    /**
     * Status of every credit card in [accounts], balances taken from the
     * same ledger entries every screen uses. Shared by the reminder worker,
     * the Dashboard and the Accounts screen so they can't disagree.
     */
    fun build(
        accounts: List<Account>,
        transactions: List<Transaction>,
        entries: List<AccountBalanceCalculator.LedgerEntry>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CardReminder> {
        val cards = accounts.filter { it.type == AccountType.CREDIT_CARD }
        if (cards.isEmpty()) return emptyList()
        val net = AccountBalanceCalculator.netByAccount(entries)
        return cards.map { card ->
            CardReminder(
                accountId = card.id,
                accountName = card.name,
                currencyCode = card.currencyCode,
                status = CreditCardStatusCalculator.status(
                    account = card,
                    balance = AccountBalanceCalculator.balanceOf(card, net),
                    transactions = transactions,
                    today = today,
                    zone = zone,
                ),
            )
        }
    }

    /** Cards with a payment due within a week or overdue, most urgent first -- the Dashboard strip. */
    fun dueSoon(cards: List<CardReminder>): List<CardReminder> =
        cards.filter { it.status.status == Status.DUE_SOON || it.status.status == Status.OVERDUE }
            .sortedBy { it.status.daysUntilDue }

    fun reminderKey(reminder: CardReminder): String = "card:${reminder.accountId}:${reminder.status.dueDate}"

    fun pending(cards: List<CardReminder>, alreadyNotified: Set<String>): List<CardReminder> =
        cards
            .filter { it.status.status == Status.DUE_SOON || it.status.status == Status.OVERDUE }
            .filter { (it.status.daysUntilDue ?: Long.MAX_VALUE) <= WINDOW_DAYS }
            .filter { reminderKey(it) !in alreadyNotified }
            .sortedBy { it.status.daysUntilDue }

    /** Keeps only keys for due dates still in play, so the stored set doesn't grow forever. */
    fun prune(alreadyNotified: Set<String>, cards: List<CardReminder>): Set<String> =
        alreadyNotified.intersect(cards.mapTo(mutableSetOf(), ::reminderKey))

    /** "due in 3d", "due today", "was due 2d ago". */
    fun whenText(daysUntilDue: Long): String = when {
        daysUntilDue < 0L -> "was due ${-daysUntilDue}d ago"
        daysUntilDue == 0L -> "due today"
        daysUntilDue == 1L -> "due tomorrow"
        else -> "due in ${daysUntilDue}d"
    }
}
