package com.xemmy.mmbank.domain.ai

/**
 * Which kind of server Gate 2 talks to. [LOCAL] is the original, hard privacy
 * boundary: loopback-only, enforced by [LoopbackGuard] plus the network
 * security config -- transaction text never leaves the device. [ONLINE] is an
 * explicit opt-in for a cloud OpenAI-compatible API (OpenAI, Groq, OpenRouter,
 * etc.); choosing it means transaction message text IS sent to that third
 * party, so it's never the default and the UI must say so plainly.
 */
enum class AiProviderMode {
    LOCAL,
    ONLINE,
}
