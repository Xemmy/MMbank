package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedMessageSplitterTest {

    @Test
    fun `blank lines separate messages`() {
        val blob = """
            AED 25.00 spent at Costa

            AED 40.00 spent at Carrefour
        """.trimIndent()
        assertEquals(
            listOf("AED 25.00 spent at Costa", "AED 40.00 spent at Carrefour"),
            PastedMessageSplitter.split(blob),
        )
    }

    @Test
    fun `a message wrapped over several lines stays one message`() {
        val blob = "AED 25.00 spent at Costa\non card 1234\n\nAED 40.00 spent at Carrefour"
        assertEquals(
            listOf("AED 25.00 spent at Costa on card 1234", "AED 40.00 spent at Carrefour"),
            PastedMessageSplitter.split(blob),
        )
    }

    @Test
    fun `without blank lines every line is its own message`() {
        val blob = "AED 25.00 spent at Costa\nAED 40.00 spent at Carrefour"
        assertEquals(
            listOf("AED 25.00 spent at Costa", "AED 40.00 spent at Carrefour"),
            PastedMessageSplitter.split(blob),
        )
    }

    @Test
    fun `windows and old-mac line endings split the same way`() {
        assertEquals(listOf("one", "two"), PastedMessageSplitter.split("one\r\ntwo"))
        assertEquals(listOf("one", "two"), PastedMessageSplitter.split("one\rtwo"))
    }

    @Test
    fun `runs of blank lines and stray whitespace collapse`() {
        val blob = "  first message  \n\n\n   \n\n  second message  "
        assertEquals(listOf("first message", "second message"), PastedMessageSplitter.split(blob))
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(PastedMessageSplitter.split("").isEmpty())
        assertTrue(PastedMessageSplitter.split("   \n\n  \n").isEmpty())
    }

    @Test
    fun `a single message comes back whole`() {
        assertEquals(listOf("AED 25.00 spent at Costa"), PastedMessageSplitter.split("AED 25.00 spent at Costa"))
    }

    @Test
    fun `duplicates are kept so the importer can report them`() {
        val blob = "same message\n\nsame message"
        assertEquals(listOf("same message", "same message"), PastedMessageSplitter.split(blob))
    }
}
