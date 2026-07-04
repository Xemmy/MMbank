package com.xemmy.mmbank.domain.model

/**
 * Maps a raw transaction sender — an Android package name (push notifications)
 * or an SMS short code / sender id — to a friendly bank name for display.
 *
 * Built-in defaults cover common UAE banks; the user can override or add
 * mappings via [SenderLabel] records (which always win over these defaults).
 */
object BankNames {

    // Known package names / SMS sender ids -> display name.
    private val KNOWN: Map<String, String> = mapOf(
        "com.adib.mobile" to "ADIB",
        "adib" to "ADIB",
        "com.adcb.mobile" to "ADCB",
        "adcb" to "ADCB",
        "ae.adcb.retail" to "ADCB",
        "com.fab.retail" to "FAB",
        "fab" to "FAB",
        "com.bankfab.mobile" to "FAB",
        "com.emiratesnbd.emiratesnbd" to "Emirates NBD",
        "emiratesnbd" to "Emirates NBD",
        "enbd" to "Emirates NBD",
        "com.mashreq.mobile" to "Mashreq",
        "mashreq" to "Mashreq",
        "com.mashreq.neo" to "Mashreq Neo",
        "ae.rakbank.mobile" to "RAKBANK",
        "rakbank" to "RAKBANK",
        "com.dib.mobile" to "Dubai Islamic Bank",
        "dib" to "Dubai Islamic Bank",
        "com.wio.business" to "Wio",
        "wio" to "Wio",
        "com.tabby.customer" to "Tabby",
        "tabby" to "Tabby",
        "com.getkast.app" to "Kast",
        "kast" to "Kast",
        "com.binance.dev" to "Binance",
        "binance" to "Binance",
        "com.cbd.mobile" to "CBD",
        "cbd" to "CBD",
    )

    /**
     * Best-effort friendly name. Checks the known map first, then falls back to
     * a heuristic derived from the package's most distinctive segment, and
     * finally returns the raw sender unchanged.
     */
    fun displayName(sender: String?): String? {
        val raw = sender?.trim().orEmpty()
        if (raw.isEmpty()) return null
        KNOWN[raw.lowercase()]?.let { return it }

        // Package names like "com.adib.mobile" -> take the segment after "com"/"ae"
        // that isn't a generic app word.
        if (raw.contains('.')) {
            val generic = setOf("com", "ae", "net", "org", "app", "mobile", "retail", "bank", "banking", "android", "customer", "business", "www")
            val segment = raw.lowercase().split('.').firstOrNull { it.isNotBlank() && it !in generic }
            if (segment != null) {
                KNOWN[segment]?.let { return it }
                return segment.uppercase()
            }
        }
        return raw
    }
}
