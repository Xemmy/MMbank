package com.xemmy.mmbank.domain.ai

import java.net.URI

/**
 * Normalizes a user-entered AI endpoint into the full /chat/completions URL
 * the engine actually POSTs to, fixing the misconfigurations that produce
 * opaque 401/404s:
 *
 * - Google Gemini: ANY generativelanguage.googleapis.com form -- the native
 *   `/v1beta/models/gemini-x:generateContent` path (which rejects Bearer auth
 *   outright with 401), a bare host, a `?key=` query -- is rewritten to the
 *   OpenAI-compatibility endpoint, the only Google path that accepts the
 *   `Authorization: Bearer` header this app sends.
 * - A bare origin (Ollama/LM Studio style, `http://127.0.0.1:11434`) gets
 *   `/v1/chat/completions` appended.
 * - A base URL ending in a version segment (`.../v1`, `.../v1beta`) gets
 *   `/chat/completions` appended.
 *
 * Anything else (custom gateway paths, unparseable input) is returned
 * unchanged -- this only completes the unambiguous cases, it never guesses.
 * Applied at save time in Settings so the corrected URL is visible to the
 * user, not silently substituted at request time.
 */
object AiEndpointNormalizer {

    private const val GOOGLE_HOST = "generativelanguage.googleapis.com"
    private const val GOOGLE_OPENAI_COMPAT = "https://$GOOGLE_HOST/v1beta/openai/chat/completions"

    /** Trailing version-ish path segment: /v1, /v2, /v1beta, /v1alpha2, ... */
    private val VERSION_SEGMENT = Regex("/v\\d+[a-z0-9]*$", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed

        if (trimmed.endsWith("/chat/completions")) return trimmed
        // Google first: every non-compat form on this host (native
        // :generateContent, bare host, ?key= query) means the same thing --
        // the user wants Gemini -- and only the compat path can work here.
        if (host == GOOGLE_HOST) return GOOGLE_OPENAI_COMPAT

        // A query string on any other host makes appending ambiguous; leave it.
        if (uri.rawQuery != null) return trimmed

        val path = uri.rawPath.orEmpty().trimEnd('/')
        return when {
            path.isEmpty() -> "$trimmed/v1/chat/completions"
            VERSION_SEGMENT.containsMatchIn(path) -> "$trimmed/chat/completions"
            else -> trimmed
        }
    }
}
