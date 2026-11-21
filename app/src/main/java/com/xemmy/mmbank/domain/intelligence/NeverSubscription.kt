package com.xemmy.mmbank.domain.intelligence

/**
 * Merchants that repeat constantly without being subscriptions: taxis, food
 * delivery, supermarkets, fuel. Matched on the lower-cased merchant text; the
 * radar skips them and the "Why isn't it detected?" list says why.
 * Real subscriptions from the same brands (Careem Plus, Uber One) are let through.
 */
object NeverSubscription {

    private data class Rule(val label: String, val pattern: Regex)

    private fun rule(label: String, pattern: String) = Rule(label, Regex(pattern, RegexOption.IGNORE_CASE))

    // Order matters: "Careem Food" is food delivery, not a ride.
    private val RULES = listOf(
        rule("Food delivery", "talabat|delivery hero|deliveroo|zomato|instashop|noon food|keeta|foodpanda|careem food"),
        rule("Ride-hailing", "careem(?!\\s*(plus|food))|\\buber(?!\\s*(one|eats))\\b|\\bbolt\\b|\\byango\\b|\\btaxi\\b"),
        rule("Groceries", "carrefour|\\blulu\\b|spinneys|waitrose|union ?coop|choithrams|supermarket|hypermarket|grocer"),
        rule("Fuel", "\\badnoc\\b|\\benoc\\b|\\beppco\\b|petrol|\\bfuel\\b"),
        rule(
            "Outing or entertainment venue",
            "theme ?park|ferrari world|warner bros|seaworld|amusement|playground|play ?area|boo ?boo|kidzania|\\bkids?\\b|" +
                "cinema|\\bvox\\b|reel cinemas|\\bnovo\\b",
        ),
        rule("Restaurant or cafe", "restaurant|resturant|\\bcafe\\b|café|coffee|\\bbeanz\\b|starbucks|bakery|pizza|burger|shawarma"),
        rule("Beauty or retail", "sephora|beauty|salon|\\bspa\\b|pharmacy"),
        rule("Healthcare visit", "clinic|medical|hospital|dental|\\bdr\\.? "),
        rule("Ride-hailing", "\\bubr\\b"),
    )

    /** "Ride-hailing, not a subscription" style text, or null when the merchant may be a subscription. */
    fun reasonFor(merchant: String?): String? {
        val name = merchant?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (BillDetector.isBiller(name)) return "Utility or telecom bill, see Bills"
        return RULES.firstOrNull { it.pattern.containsMatchIn(name) }?.let { "${it.label}, not a subscription" }
    }
}
