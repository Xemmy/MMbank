package com.xemmy.mmbank.data.ai

import java.io.File

/**
 * Minimal surface LocalAiManager needs from an on-device LLM runtime: one
 * blocking single-turn generation. Everything above this interface (prompting,
 * output parsing, rule learning) is runtime-agnostic pure Kotlin.
 */
interface LocalAiEngine : AutoCloseable {

    /** Runs one generation for [prompt] and returns the raw model text. */
    suspend fun generate(prompt: String): String

    override fun close()
}

/**
 * Structural seam for the LiteRT-LM binding of the Gemma E2B-class model.
 *
 * The native runtime dependency is NOT bundled yet, so [create] returns null
 * and LocalAiManager reports the engine unavailable — the whole Gate 2
 * pipeline then stands down cleanly (no workers are even enqueued). When the
 * runtime lands, this factory becomes the only file that changes:
 *
 * 1. add the LiteRT-LM engine dependency to libs.versions.toml / app gradle;
 * 2. build the engine here from [modelFile] (a `.litertlm` bundle pushed to
 *    files/models/) with the [maxTokens] context cap and the NPU/GPU delegate
 *    preferred over CPU (target hardware: Snapdragon 8 Elite);
 * 3. wrap it in a [LocalAiEngine] whose close() frees the native session.
 *
 * Privacy contract: the engine must be constructed for fully offline
 * inference — no network I/O, and message/token text never leaves the
 * process or gets logged.
 */
object LiteRtLmEngineFactory {

    @Suppress("UNUSED_PARAMETER")
    fun create(modelFile: File, maxTokens: Int): LocalAiEngine? = null
}
