package com.xemmy.mmbank.domain.model

/**
 * Layout of the Activity tab's filter row. SINGLE_ROW packs Time/Type/Account
 * into one scrollable row of dropdown chips plus a "More filters" dialog for
 * the rest; CLASSIC wraps every chip across as many lines as it takes (the
 * original behavior).
 */
enum class FilterBarLayout {
    SINGLE_ROW,
    CLASSIC,
}
