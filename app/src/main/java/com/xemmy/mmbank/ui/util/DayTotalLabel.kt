package com.xemmy.mmbank.ui.util

import com.xemmy.mmbank.domain.report.DayTotal
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import kotlin.math.abs

/** "+AED 120.00" / "−AED 40.00" for a day header; masked like every balance while hidden. */
fun dayTotalLabel(total: DayTotal, currency: String, hidden: Boolean): String {
    val body = moneyLabel(abs(total.net), currency, hidden)
    return when (total.netSign) {
        1 -> "+$body"
        -1 -> "−$body"
        else -> body
    }
}
