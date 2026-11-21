package com.xemmy.mmbank.domain.parser

/**
 * The integer part of a money amount, shared by every amount regex so they
 * all read the same numbers the same way:
 *  - Indian grouping "1,00,000" / "12,34,567" (lakh/crore, common in INR
 *    messages); before this, "INR 1,00,000.00" was read as 1;
 *  - Western grouping "1,234,567";
 *  - up to 9 plain digits. A 10+ digit run without separators is an order,
 *    card or account number, never a personal-finance amount.
 * Never starts in the middle of a longer digit run.
 */
object AmountPatterns {
    const val INTEGER = "(?<!\\d)(?:\\d{1,2}(?:,\\d{2})+,\\d{3}|\\d{1,3}(?:,\\d{3})+|\\d{1,9})"
}
