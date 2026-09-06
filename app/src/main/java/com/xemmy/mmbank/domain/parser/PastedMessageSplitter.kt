package com.xemmy.mmbank.domain.parser

/**
 * Cuts a blob of pasted text into the individual messages it contains.
 *
 * People paste from two different places and the shapes differ: an SMS export
 * or a chat copy puts a blank line between messages (a single message may then
 * wrap over several lines), while a quick hand-assembled list is one message
 * per line. So blank lines win when present -- they are the only unambiguous
 * separator -- and only a blob without any of them falls back to one message
 * per line.
 *
 * Duplicates are returned as-is rather than collapsed here: the importer is
 * what reports "skipped, already imported", and it can only do that honestly
 * if it sees every message the user actually pasted.
 */
object PastedMessageSplitter {

    private val BLANK_LINE_RUN = Regex("\\n\\s*\\n+")
    private val WHITESPACE = Regex("\\s+")

    fun split(blob: String): List<String> {
        val text = blob.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()

        val chunks = if (BLANK_LINE_RUN.containsMatchIn(text)) {
            text.split(BLANK_LINE_RUN)
        } else {
            text.split('\n')
        }
        // A wrapped message spans lines; rejoining them keeps the parser's
        // regexes -- written against single-line bank text -- working.
        return chunks.mapNotNull { chunk ->
            chunk.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }
        }
    }
}
