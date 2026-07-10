package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.SpendingCategory

/**
 * Best-effort spending-category guesser. Runs at capture time on the parsed
 * merchant (falling back to the raw message text) and is a pure function, so
 * it is cheap and unit-testable. A miss returns [SpendingCategory.UNCATEGORIZED]
 * — the caller only ever fills categories that are still unset, so an explicit
 * user choice is never overwritten.
 *
 * Keyword lists lean on the UAE merchants this app sees most, but the matching
 * is plain case-insensitive substring so it degrades gracefully elsewhere.
 */
object MerchantCategorizer {

    // Order matters: the first bucket whose keywords hit wins, so put the more
    // specific buckets (fuel, transport) ahead of broader ones (shopping).
    private val rules: List<Pair<SpendingCategory, List<String>>> = listOf(
        SpendingCategory.GROCERIES to listOf(
            "carrefour", "lulu", "spinneys", "grocery", "groceries", "supermarket",
            "hypermarket", "waitrose", "choithram", "union coop", "coop", "baqala",
            "west zone", "viva", "grandiose", "aswaaq", "mart", "nesto",
        ),
        SpendingCategory.FUEL to listOf(
            "adnoc", "enoc", "eppco", "emarat", "petrol", "fuel", "gas station",
        ),
        SpendingCategory.TRANSPORT to listOf(
            "careem", "uber", "taxi", "salik", "rta", "metro", "parking", "bolt",
            "yango", "hala", "nol", "darb",
        ),
        SpendingCategory.DINING to listOf(
            "talabat", "deliveroo", "zomato", "noon food", "restaurant", "cafe",
            "coffee", "starbucks", "mcdonald", "kfc", "burger", "pizza", "dining",
            "bakery", "eatery", "shawarma", "costa", "tim horton",
        ),
        SpendingCategory.ENTERTAINMENT to listOf(
            "netflix", "spotify", "youtube", "apple.com", "apple.com/bill", "itunes",
            "google play", "playstation", "xbox", "steam", "cinema", "vox", "reel",
            "disney", "shahid", "osn", "anghami", "prime video", "twitch",
        ),
        SpendingCategory.UTILITIES to listOf(
            "dewa", "sewa", "addc", "fewa", "etisalat", "du ", "du.", "utility",
            "electricity", "water bill", "internet", "telecom", "mobile bill",
        ),
        SpendingCategory.HEALTH to listOf(
            "pharmacy", "aster", "medcare", "clinic", "hospital", "medical", "dental",
            "dentist", "life pharmacy", "bin sina", "nmc", "healthhub", "labs",
        ),
        SpendingCategory.TRAVEL to listOf(
            "emirates", "etihad", "flydubai", "air arabia", "booking.com", "airbnb",
            "hotel", "agoda", "expedia", "airline", "airways", "wizz", "qatar airways",
        ),
        SpendingCategory.SHOPPING to listOf(
            "amazon", "noon", "namshi", "sharaf dg", "ikea", "h&m", "zara", "centrepoint",
            "shein", "ace", "apple store", "mall", "store", "shop", "fashion", "nike",
            "adidas", "electronics", "jumbo",
        ),
        SpendingCategory.FEES to listOf(
            "fee", "charge", "vat", "interest", "profit", "penalty", "commission",
        ),
        SpendingCategory.TRANSFERS to listOf(
            "transfer", "remittance", "western union", "exchange", "atm", "cash withdrawal",
            "iban", "sent to", "received from",
        ),
    )

    fun categorize(merchant: String?, rawText: String? = null): SpendingCategory {
        val haystack = buildString {
            merchant?.let { append(it).append(' ') }
            rawText?.let { append(it) }
        }.lowercase()
        if (haystack.isBlank()) return SpendingCategory.UNCATEGORIZED

        for ((category, keywords) in rules) {
            if (keywords.any { haystack.contains(it) }) return category
        }
        return SpendingCategory.UNCATEGORIZED
    }
}
