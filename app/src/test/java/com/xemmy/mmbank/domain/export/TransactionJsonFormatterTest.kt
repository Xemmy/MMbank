package com.xemmy.mmbank.domain.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionJsonFormatterTest {

    private fun render(rows: List<ExportRow>, summary: String = "Past 30 days") =
        TransactionJsonFormatter.format(rows, "AED", summary, millis(), UTC)

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun outputIsValidJsonWithHeaderFields() {
        val doc = parse(render(listOf(testRow(), testRow(id = 2))))
        assertEquals("MMbank", doc["app"]!!.jsonPrimitive.content)
        assertEquals("AED", doc["baseCurrency"]!!.jsonPrimitive.content)
        assertEquals("Past 30 days", doc["filters"]!!.jsonPrimitive.content)
        assertEquals(2, doc["count"]!!.jsonPrimitive.int)
        assertEquals(2, doc["transactions"]!!.jsonArray.size)
        assertEquals("2026-11-20T09:05:00Z", doc["exportedAt"]!!.jsonPrimitive.content.replace("+00:00", "Z"))
        assertTrue(doc["notes"]!!.jsonPrimitive.content.contains("negative = money out"))
    }

    @Test fun emptyListStillParses() {
        val doc = parse(render(emptyList()))
        assertEquals(0, doc["count"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), doc["transactions"])
    }

    @Test fun amountsAreRealNumbersNotStrings() {
        val tx = parse(render(listOf(testRow(amount = "-46.12", base = "-46.12"))))["transactions"]!!.jsonArray[0].jsonObject
        val amount = tx["amount"]!!.jsonPrimitive
        assertFalse(amount.isString)
        assertEquals("-46.12", amount.content)
        assertFalse(tx["amountInBase"]!!.jsonPrimitive.isString)
        assertFalse(tx["id"]!!.jsonPrimitive.isString)
    }

    @Test fun tagsAreAnArrayAndFlagsAreBooleans() {
        val row = testRow(tags = listOf("Holiday", "Tax")).copy(excludedFromStats = true)
        val tx = parse(render(listOf(row)))["transactions"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("Holiday", "Tax"), tx["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tx["excludedFromStats"]!!.jsonPrimitive.boolean)
    }

    @Test fun emptyFieldsAreLeftOut() {
        val tx = parse(render(listOf(testRow(merchant = null))))["transactions"]!!.jsonArray[0].jsonObject
        assertNull(tx["merchant"])
        assertNull(tx["note"])
        assertNull(tx["tags"])
        assertNull(tx["amountInBase"])
        assertNull(tx["excludedFromStats"])
        assertEquals("Spent", tx["kind"]!!.jsonPrimitive.content)
        assertEquals("Main", tx["account"]!!.jsonPrimitive.content)
    }

    @Test fun trickyTextRoundTrips() {
        val nasty = "He said \"hi\"\\ \n new line\ttab \u0001 سوبرماركت 😀"
        val tx = parse(render(listOf(testRow(note = nasty)), summary = nasty))
        assertEquals(nasty, tx["filters"]!!.jsonPrimitive.content)
        assertEquals(nasty, tx["transactions"]!!.jsonArray[0].jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test fun dateCarriesTheZoneOffset() {
        val dubai = java.time.ZoneId.of("Asia/Dubai")
        val text = TransactionJsonFormatter.format(listOf(testRow()), "AED", "x", millis(), dubai)
        val date = parse(text)["transactions"]!!.jsonArray[0].jsonObject["date"]!!.jsonPrimitive.content
        assertEquals("2026-11-20T13:05:00+04:00", date)
    }

    @Test fun orderIsKept() {
        val ids = parse(render(listOf(testRow(id = 9), testRow(id = 3))))["transactions"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.int }
        assertEquals(listOf(9, 3), ids)
    }

    @Test fun controlCharactersAreEscaped() {
        assertEquals("\"a\\u0001b\"", TransactionJsonFormatter.str("a\u0001b"))
        assertEquals("\"q\\\"\\\\\"", TransactionJsonFormatter.str("q\"\\"))
        assertEquals(JsonPrimitive("x").content, parse("{\"k\": ${TransactionJsonFormatter.str("x")}}")["k"]!!.jsonPrimitive.content)
    }
}
