package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account

/**
 * Folds a freshly typed or pasted [Account] into one that already exists
 * (see [AccountUpsertResolver]) without losing anything the user already
 * had. Pure, so the "never overwrite" rule is unit tested.
 *
 *  - A field the existing account leaves blank is filled from the draft.
 *  - New card endings are appended to the existing ones.
 *  - A field both sides have but that disagrees is **kept as it was** and
 *    reported as a [Conflict], so the caller can say so instead of silently
 *    choosing. Type and currency are never changed by a merge.
 *  - The opening balance, name and archive state are never touched here;
 *    the caller decides about those.
 */
object AccountMerge {

    data class Conflict(val field: String, val existing: String, val incoming: String)

    data class Result(
        val account: Account,
        /** Labels of the fields that were blank and are now filled, for "Added: ...". */
        val filled: List<String>,
        val conflicts: List<Conflict>,
    ) {
        val changed: Boolean get() = filled.isNotEmpty()
    }

    fun merge(existing: Account, draft: Account): Result {
        var merged = existing
        val filled = mutableListOf<String>()
        val conflicts = mutableListOf<Conflict>()

        fun text(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }

        /** Fills a blank [current]; reports a differing non-blank one. Returns the value to keep. */
        fun fill(label: String, current: String?, incoming: String?, same: (String, String) -> Boolean): String? {
            val old = text(current)
            val new = text(incoming) ?: return old
            if (old == null) {
                filled += label
                return new
            }
            if (!same(old, new)) conflicts += Conflict(label, old, new)
            return old
        }

        val equalText: (String, String) -> Boolean = { a, b -> a.equals(b, ignoreCase = true) }
        val equalIban: (String, String) -> Boolean = { a, b -> AccountUpsertResolver.normalizeIban(a) == AccountUpsertResolver.normalizeIban(b) }
        // "1234" and "001234" are the same number; masking and length differ between banks.
        val sameNumber: (String, String) -> Boolean = { a, b ->
            val x = a.filter(Char::isDigit)
            val y = b.filter(Char::isDigit)
            x.isNotEmpty() && y.isNotEmpty() && (x.endsWith(y) || y.endsWith(x))
        }

        merged = merged.copy(
            bankName = fill("bank name", existing.bankName, draft.bankName, equalText),
            accountTitle = fill("account title", existing.accountTitle, draft.accountTitle, equalText),
            accountNumber = fill("account number", existing.accountNumber, draft.accountNumber, sameNumber),
            iban = fill("IBAN", existing.iban, draft.iban, equalIban),
            swift = fill("SWIFT", existing.swift, draft.swift, equalText),
            notes = fill("notes", existing.notes, draft.notes) { _, _ -> true },
        )

        if (draft.type != existing.type) conflicts += Conflict("type", existing.type.name, draft.type.name)
        if (!draft.currencyCode.equals(existing.currencyCode, ignoreCase = true)) {
            conflicts += Conflict("currency", existing.currencyCode, draft.currencyCode)
        }

        // A draft's account number that doesn't match the existing main number is
        // another card on the same account: keep it as an extra ending.
        val extraEndings = buildList {
            addAll(endingsOf(draft.cardEndings))
            val draftNumber = draft.accountNumber.orEmpty().filter(Char::isDigit)
            val existingNumber = merged.accountNumber.orEmpty().filter(Char::isDigit)
            if (draftNumber.length >= MIN_DIGITS && existingNumber.isNotEmpty() && !sameNumber(existingNumber, draftNumber)) {
                add(draftNumber)
            }
        }
        val have = endingsOf(merged.cardEndings)
        val added = extraEndings.filter { new -> have.none { sameNumber(it, new) } }.distinct()
        if (added.isNotEmpty()) {
            filled += "card ending ${added.joinToString(", ") { "••$it" }}"
            merged = merged.copy(cardEndings = (have + added).joinToString(", "))
        }
        // A number that now lives in the card endings is another card, not a conflict.
        val draftDigits = draft.accountNumber.orEmpty().filter(Char::isDigit)
        if (endingsOf(merged.cardEndings).any { sameNumber(it, draftDigits) }) {
            conflicts.removeAll { it.field == "account number" }
        }

        return Result(merged, filled, conflicts)
    }

    private const val MIN_DIGITS = AccountNumberMatcher.MIN_DIGITS

    private fun endingsOf(raw: String?): List<String> =
        raw.orEmpty().split(',', ';', '/', '\n', ' ')
            .map { it.filter(Char::isDigit) }
            .filter { it.length >= MIN_DIGITS }
            .distinct()
}
