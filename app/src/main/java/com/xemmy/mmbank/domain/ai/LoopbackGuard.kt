package com.xemmy.mmbank.domain.ai

import java.net.URI

/**
 * The hard privacy boundary for Gate-2 local AI: transaction text may only
 * ever be sent to a loopback address on this device. [OpenAiCompatibleEngine]
 * calls this at construction AND before every request, so a misconfigured or
 * tampered endpoint can never exfiltrate captured bank-message text to a LAN
 * box or the internet -- honoring the spec's "100% offline, zero outbound".
 *
 * Pure (java.net.URI only) so the whole guard is unit-testable on the JVM.
 */
object LoopbackGuard {

    /**
     * True only when [url] is a well-formed http/https URL whose host is a
     * loopback address: `localhost`, `127.0.0.0/8`, or IPv6 `::1`. Anything
     * else -- `0.0.0.0`, a LAN IP, a hostname, an opaque/relative URI, or a
     * non-http scheme -- is rejected.
     */
    fun isLoopbackUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return isLoopbackHost(host)
    }

    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost") return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        // 127.0.0.0/8 -- any 127.x.y.z is loopback.
        val parts = h.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            return parts[0] == "127"
        }
        return false
    }
}
