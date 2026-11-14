package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType

/**
 * "Does this account already exist?" -- the check behind quick add and the
 * full account form, so adding the same account or card twice updates it
 * instead of creating a duplicate. Pure, so every matching rule is unit tested.
 *
 * Evidence, strongest first:
 *  1. **IBAN**, normalized (spaces and case ignored), exact.
 *  2. **Account / card number**, through [AccountNumberMatcher]: digits only,
 *     at least [AccountNumberMatcher.MIN_DIGITS], shared suffix. Several accounts
 *     matching the same number is [Result.Ambiguous], never a guess.
 *  3. **Same name + bank + currency + type** -- only a [Strength.WEAK] match,
 *     because two real accounts can share a name.
 *
 * Archived accounts are candidates too: re-adding a closed account should
 * offer to bring it back, not start a second copy.
 */
object AccountUpsertResolver {

    /** What the user typed; blank strings count as absent. */
    data class Draft(
        val type: AccountType,
        val name: String,
        val currencyCode: String,
        val bankName: String? = null,
        val accountNumber: String? = null,
        val cardEndings: String? = null,
        val iban: String? = null,
    )

    enum class Strength { STRONG, WEAK }

    enum class MatchedOn(val label: String) {
        IBAN("IBAN"),
        NUMBER("account or card number"),
        NAME("name, bank and currency"),
    }

    sealed interface Result {
        /** Nothing like it exists: safe to create. */
        object New : Result

        data class Existing(val account: Account, val matchedOn: MatchedOn, val strength: Strength) : Result

        /** Several accounts fit equally well; the user picks. */
        data class Ambiguous(val accounts: List<Account>, val matchedOn: MatchedOn) : Result
    }

    fun normalizeIban(raw: String?): String? =
        raw?.filter { !it.isWhitespace() }?.uppercase()?.takeIf { it.isNotEmpty() }

    private fun norm(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

    /**
     * [ignoreId] leaves one account out of the comparison, so editing an
     * account is never reported as a duplicate of itself.
     */
    fun resolve(draft: Draft, accounts: List<Account>, ignoreId: Long = 0L): Result {
        val pool = accounts.filter { it.id != ignoreId || ignoreId == 0L }

        fun pick(found: List<Account>, matchedOn: MatchedOn, strength: Strength): Result? = when {
            found.isEmpty() -> null
            found.size == 1 -> Result.Existing(found.single(), matchedOn, strength)
            else -> Result.Ambiguous(found, matchedOn)
        }

        // 1. IBAN.
        val iban = normalizeIban(draft.iban)
        if (iban != null) {
            pick(pool.filter { normalizeIban(it.iban) == iban }, MatchedOn.IBAN, Strength.STRONG)?.let { return it }
        }

        // 2. Account / card numbers.
        val numbers = AccountNumberMatcher.numbersOf(
            AccountNumberMatcher.Candidate(0L, draft.accountNumber, draft.cardEndings, draft.currencyCode),
        )
        if (numbers.isNotEmpty()) {
            val candidates = pool.map {
                AccountNumberMatcher.Candidate(it.id, it.accountNumber, it.cardEndings, it.currencyCode)
            }
            val ids = numbers.mapNotNull { AccountNumberMatcher.match(it, draft.currencyCode, candidates) }.distinct()
            pick(pool.filter { it.id in ids }, MatchedOn.NUMBER, Strength.STRONG)?.let { return it }
        }

        // 3. Same name, bank, currency and type.
        val name = norm(draft.name)
        if (name.isNotEmpty()) {
            val weak = pool.filter {
                norm(it.name) == name &&
                    norm(it.bankName) == norm(draft.bankName) &&
                    it.currencyCode.equals(draft.currencyCode.trim(), ignoreCase = true) &&
                    it.type == draft.type
            }
            pick(weak, MatchedOn.NAME, Strength.WEAK)?.let { return it }
        }
        return Result.New
    }

    fun draftOf(account: Account) = Draft(
        type = account.type,
        name = account.name,
        currencyCode = account.currencyCode,
        bankName = account.bankName,
        accountNumber = account.accountNumber,
        cardEndings = account.cardEndings,
        iban = account.iban,
    )
}
