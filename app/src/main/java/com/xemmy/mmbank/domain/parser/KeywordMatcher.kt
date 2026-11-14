package com.xemmy.mmbank.domain.parser

/** Case-insensitive "does this text mention any muted keyword" check. */
object KeywordMatcher {

    /** [keywords] are expected lowercase (the repository normalizes them); blanks never match. */
    fun matches(text: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty() || text.isBlank()) return false
        val haystack = text.lowercase()
        return keywords.any { it.isNotBlank() && haystack.contains(it.lowercase()) }
    }
}
