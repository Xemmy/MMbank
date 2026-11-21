package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.account.AccountMerge
import com.xemmy.mmbank.domain.account.AccountUpsertResolver
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import java.math.RoundingMode

/** What the quick-add sheet collects, as typed. */
internal data class QuickAddInput(
    val type: AccountType = AccountType.CHECKING,
    val name: String = "",
    val currency: String = "",
    /** The balance the bank (or wallet) shows right now; blank = not entered. */
    val balance: String = "",
    /** Last digits of the account or card; optional, but the strongest way to recognise it later. */
    val lastDigits: String = "",
    // Filled by "Paste bank details".
    val bankName: String = "",
    val accountTitle: String = "",
    val accountNumber: String = "",
    val iban: String = "",
    val swift: String = "",
)

/** What saving a quick add (or a full-form save of a new account) should do. */
internal sealed interface QuickAddPlan {

    data class Invalid(val errors: Map<AccountField, String>) : QuickAddPlan

    /** Nothing like it exists. */
    data class Create(val account: Account) : QuickAddPlan

    /** It exists already: update it rather than adding a copy. */
    data class Update(
        val existing: Account,
        val matchedOn: AccountUpsertResolver.MatchedOn,
        val strength: AccountUpsertResolver.Strength,
        val merge: AccountMerge.Result,
        /** What the app computes today for [existing]. */
        val currentBalance: BigDecimal,
        /** What the user typed; null when they left it blank. */
        val enteredBalance: BigDecimal?,
        /** The typed details as a brand-new account, for "Create separate". */
        val draft: Account,
    ) : QuickAddPlan {
        val archived: Boolean get() = existing.archived

        val balanceDiffers: Boolean
            get() = enteredBalance != null && enteredBalance.money() != currentBalance.money()
    }

    /** Several accounts fit equally well; the user picks one. */
    data class Choose(
        val accounts: List<Account>,
        val matchedOn: AccountUpsertResolver.MatchedOn,
        val draft: Account,
    ) : QuickAddPlan
}

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

internal object QuickAddPlanner {

    private fun toFormInput(input: QuickAddInput): AccountFormInput {
        val digits = input.lastDigits.filter(Char::isDigit)
        return AccountFormInput(
            name = input.name,
            type = input.type,
            currency = input.currency,
            openingBalance = input.balance,
            accountNumber = input.accountNumber.ifBlank { digits },
            // Card endings are only kept for types that have cards; the number covers the rest.
            cardEndings = if (input.accountNumber.isNotBlank()) digits else "",
            bankName = input.bankName,
            accountTitle = input.accountTitle,
            iban = input.iban,
            swift = input.swift,
        )
    }

    /**
     * Whatever is typed so far, valid or not, for handing over to the full
     * form ("More details"): nothing the user entered is dropped.
     */
    fun draftForFullForm(input: QuickAddInput, fallbackCurrency: String): Account {
        val form = toFormInput(input)
        return Account(
            name = input.name.trim(),
            type = input.type,
            currencyCode = input.currency.trim().uppercase().ifEmpty { fallbackCurrency },
            openingBalance = parseMoneyInput(input.balance) ?: BigDecimal.ZERO,
            accountNumber = form.accountNumber.trim().ifEmpty { null },
            cardEndings = if (input.type.hasCards()) AccountFormValidator.normalizeCardEndings(form.cardEndings) else null,
            bankName = input.bankName.trim().ifEmpty { null },
            accountTitle = input.accountTitle.trim().ifEmpty { null },
            iban = input.iban.trim().ifEmpty { null },
            swift = input.swift.trim().ifEmpty { null },
        )
    }

    /**
     * Decides what a quick add does. [accounts] is every account, active and
     * archived; [balances] their computed balances by id.
     */
    fun plan(input: QuickAddInput, accounts: List<Account>, balances: Map<Long, BigDecimal>): QuickAddPlan {
        val form = toFormInput(input)
        val errors = AccountFormValidator.errors(form)
        if (errors.isNotEmpty()) return QuickAddPlan.Invalid(errors)
        val draft = AccountFormValidator.apply(Account(name = "", type = input.type, currencyCode = "AED"), form)
        val entered = if (input.balance.isBlank()) null else parseMoneyInput(input.balance)

        return when (val found = AccountUpsertResolver.resolve(AccountUpsertResolver.draftOf(draft), accounts)) {
            AccountUpsertResolver.Result.New -> QuickAddPlan.Create(draft)
            is AccountUpsertResolver.Result.Ambiguous -> QuickAddPlan.Choose(found.accounts, found.matchedOn, draft)
            is AccountUpsertResolver.Result.Existing -> updateOf(found.account, found.matchedOn, draft, entered, balances)
        }
    }

    /** The plan for updating [existing], which the user picked from an ambiguous list. */
    fun updateOf(
        existing: Account,
        matchedOn: AccountUpsertResolver.MatchedOn,
        draft: Account,
        entered: BigDecimal?,
        balances: Map<Long, BigDecimal>,
    ) = QuickAddPlan.Update(
        existing = existing,
        matchedOn = matchedOn,
        strength = if (matchedOn == AccountUpsertResolver.MatchedOn.NAME) {
            AccountUpsertResolver.Strength.WEAK
        } else {
            AccountUpsertResolver.Strength.STRONG
        },
        merge = AccountMerge.merge(existing, draft),
        currentBalance = balances[existing.id] ?: existing.openingBalance,
        enteredBalance = entered,
        draft = draft,
    )

    /**
     * The duplicate guard for the full form's Save: null when [saved] is fine
     * to write as it is. A new account is checked against everything; an
     * existing one only against *strong* matches (same IBAN or number), so
     * renaming an account is never nagged about a lookalike.
     */
    fun guardFullForm(saved: Account, accounts: List<Account>): AccountUpsertResolver.Result? {
        val result = AccountUpsertResolver.resolve(AccountUpsertResolver.draftOf(saved), accounts, ignoreId = saved.id)
        return when {
            result == AccountUpsertResolver.Result.New -> null
            saved.id == 0L -> result
            result is AccountUpsertResolver.Result.Existing && result.strength == AccountUpsertResolver.Strength.STRONG -> result
            result is AccountUpsertResolver.Result.Ambiguous -> result
            else -> null
        }
    }

    /** Label of the main button on the "already exists" prompt. */
    fun primaryLabel(plan: QuickAddPlan.Update): String = when {
        plan.archived -> "Restore and update"
        plan.balanceDiffers -> "Update balance"
        plan.merge.changed -> "Add details"
        else -> "Got it"
    }

    /** The lines of the "already exists" prompt; [money] renders an amount (masked when balances are hidden). */
    fun summary(plan: QuickAddPlan.Update, money: (BigDecimal) -> String): List<String> = buildList {
        add("Matched on ${plan.matchedOn.label}.")
        if (plan.archived) add("It is archived; updating it brings it back.")
        if (plan.enteredBalance != null) {
            add(
                if (plan.balanceDiffers) {
                    "Balance now ${money(plan.currentBalance)}; you entered ${money(plan.enteredBalance)}. Updating records the difference, with a reason, in its history."
                } else {
                    "Balance already ${money(plan.currentBalance)}, as you entered."
                },
            )
        }
        if (plan.merge.filled.isNotEmpty()) add("Adds: ${plan.merge.filled.joinToString(", ")}.")
        if (plan.merge.conflicts.isNotEmpty()) {
            add("Kept as they were (different from what you typed): ${plan.merge.conflicts.joinToString(", ") { it.field }}.")
        }
    }
}
