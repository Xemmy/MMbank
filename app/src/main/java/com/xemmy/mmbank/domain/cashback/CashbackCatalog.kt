package com.xemmy.mmbank.domain.cashback

import java.math.BigDecimal

/**
 * Built-in UAE cards and spend categories, seeded from the user's cashback
 * optimizer. Rates/caps/fees are editable assumptions — banks change terms, so
 * treat these as a starting point.
 */
object CashbackCatalog {

    // --- Categories ---
    const val GROCERIES_CARREFOUR = "groceries_carrefour"
    const val GROCERIES_LULU = "groceries_lulu"
    const val FUEL_SALIK = "fuel_salik"
    const val UTILITIES_TELECOM = "utilities_telecom"
    const val DINING_DELIVERY = "dining_delivery"
    const val CINEMA_ENTERTAINMENT = "cinema_entertainment"
    const val ONLINE_FASHION = "online_fashion"
    const val FOREIGN_ONLINE = "foreign_online"
    const val AMAZON_NOON = "amazon_noon"
    const val USD_SUBSCRIPTIONS = "usd_subscriptions"
    const val TRAVEL_FX = "travel_fx"
    const val GENERAL_RETAIL = "general_retail"

    val categories: List<CashbackCategory> = listOf(
        CashbackCategory(GROCERIES_CARREFOUR, "Groceries — Carrefour/other", "Carrefour, Union Coop, Spinneys"),
        CashbackCategory(GROCERIES_LULU, "Groceries — LuLu", "LuLu Hypermarket"),
        CashbackCategory(FUEL_SALIK, "Fuel + Salik", "ADNOC, ENOC, Salik"),
        CashbackCategory(UTILITIES_TELECOM, "Utilities & telecom", "DEWA, du, Etisalat"),
        CashbackCategory(DINING_DELIVERY, "Dining & delivery", "Restaurants, Talabat, Deliveroo"),
        CashbackCategory(CINEMA_ENTERTAINMENT, "Cinema & entertainment", "VOX, Reel, events"),
        CashbackCategory(ONLINE_FASHION, "Online fashion", "Shein, Namshi"),
        CashbackCategory(FOREIGN_ONLINE, "Foreign online shopping", "Temu, AliExpress"),
        CashbackCategory(AMAZON_NOON, "Amazon.ae / noon (AED)", "Amazon.ae, noon"),
        CashbackCategory(USD_SUBSCRIPTIONS, "USD subscriptions", "Netflix, Spotify, ChatGPT"),
        CashbackCategory(TRAVEL_FX, "Travel — foreign currency", "Flights, hotels abroad"),
        CashbackCategory(GENERAL_RETAIL, "General retail & other (AED)", "Electronics, malls, pharmacy"),
    )

    private fun aed(v: String) = BigDecimal(v)

    // --- Cards (order matters: earlier cards win rate ties) ---
    val cards: List<CashbackCard> = listOf(
        CashbackCard(
            id = "adcb_365",
            name = "ADCB 365",
            ratesByCategory = mapOf(
                DINING_DELIVERY to 0.06,
                GROCERIES_CARREFOUR to 0.05,
                FUEL_SALIK to 0.03,
                UTILITIES_TELECOM to 0.03,
            ),
            baseRate = 0.01,
            monthlyCap = aed("1000"),
            minMonthlySpend = aed("5000"),
            annualFee = aed("383"),
            notes = "6% dining / 5% grocery / 3% fuel-utilities / 1% other; needs AED 5,000/mo.",
        ),
        CashbackCard(
            id = "fab_cashback",
            name = "FAB Cashback",
            ratesByCategory = mapOf(
                FUEL_SALIK to 0.05,
                GROCERIES_CARREFOUR to 0.05,
                UTILITIES_TELECOM to 0.05,
            ),
            baseRate = 0.0,
            monthlyCap = aed("300"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "5% fuel/groceries/utilities; keep ~AED 24k/yr to stay fee-free.",
        ),
        CashbackCard(
            id = "mashreq_cashback",
            name = "Mashreq Cashback",
            ratesByCategory = mapOf(DINING_DELIVERY to 0.05),
            baseRate = 0.0,
            monthlyCap = aed("750"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "5% dining; conservative cap assumption.",
        ),
        CashbackCard(
            id = "adcb_lulu",
            name = "ADCB LuLu Platinum",
            ratesByCategory = mapOf(GROCERIES_LULU to 0.05),
            baseRate = 0.0,
            monthlyCap = aed("300"),
            minMonthlySpend = aed("3500"),
            annualFee = aed("0"),
            notes = "~5% value at LuLu; needs AED 3,500/mo combined spend.",
        ),
        CashbackCard(
            id = "tabby_plus",
            name = "Tabby+",
            ratesByCategory = mapOf(
                ONLINE_FASHION to 0.05,
                CINEMA_ENTERTAINMENT to 0.05,
            ),
            baseRate = 0.0,
            monthlyCap = aed("200"),
            minMonthlySpend = aed("0"),
            annualFee = aed("588"),
            notes = "AED 49/mo plan; 5% across chosen categories, cap AED 200/mo.",
        ),
        CashbackCard(
            id = "enbd_noon",
            name = "ENBD noon One",
            ratesByCategory = mapOf(AMAZON_NOON to 0.05),
            baseRate = 0.0,
            monthlyCap = aed("200"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "Up to 20% noon; 5% blended estimate.",
        ),
        CashbackCard(
            id = "kast",
            name = "Kast",
            ratesByCategory = mapOf(
                FOREIGN_ONLINE to 0.015,
                TRAVEL_FX to 0.015,
                USD_SUBSCRIPTIONS to 0.02,
            ),
            baseRate = 0.015,
            monthlyCap = aed("110"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "~1.5%; 0% FX promo — best for foreign currency.",
        ),
        CashbackCard(
            id = "binance",
            name = "Binance Card",
            ratesByCategory = emptyMap(),
            baseRate = 0.02,
            monthlyCap = aed("200"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "2% assumes 1+ BNB held; 0.1% otherwise.",
        ),
        CashbackCard(
            id = "wio",
            name = "Wio Credit",
            ratesByCategory = emptyMap(),
            baseRate = 0.01,
            monthlyCap = aed("200"),
            minMonthlySpend = aed("0"),
            annualFee = aed("0"),
            notes = "Flat ~1% assumption.",
        ),
    )
}
