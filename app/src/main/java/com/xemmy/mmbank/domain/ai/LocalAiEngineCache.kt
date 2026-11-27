package com.xemmy.mmbank.domain.ai

/**
 * Caches a single [LocalAiEngine] instance behind a factory, so repeated
 * ingestion calls never pay LiteRT-LM's load cost more than once per process.
 */
class LocalAiEngineCache(private val factory: () -> LocalAiEngine) {
    @Volatile
    private var cached: LocalAiEngine? = null
    private val lock = Any()

    fun get(): LocalAiEngine {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            return factory().also { cached = it }
        }
    }

    /** Drops the cached instance, e.g. after a failed initialize() so the next get() retries fresh. */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }
}
