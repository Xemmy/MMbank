package com.xemmy.mmbank.domain.parser

/**
 * Per-sender capture quality, read back out of the capture log (backlog B.3
 * #17): "ADCB: 212 saved, 3 unmatched" turns a vague "capture feels flaky"
 * into a named sender whose messages need a rule. Built from the lines
 * [CaptureLogFormatter.line] writes -- `[stamp] CHANNEL sender -> OUTCOME` --
 * so it covers exactly what the (rolling) log still holds.
 *
 * Pure and Android-free.
 */
object CaptureSenderStats {

    data class SenderStat(
        val sender: String,
        val saved: Int,
        val duplicate: Int,
        val ignored: Int,
        val unmatched: Int,
        val errors: Int,
    ) {
        val total: Int get() = saved + duplicate + ignored + unmatched + errors

        /** Share of this sender's logged messages that no rule could read, 0.0..1.0. */
        val unmatchedShare: Double get() = if (total == 0) 0.0 else unmatched.toDouble() / total
    }

    const val UNKNOWN_SENDER = "(unknown sender)"

    private val LINE = Regex("""^\[[^\]]*]\s+(SMS|PUSH)\s+(.*?)\s*->\s+(\S.*)$""")

    /**
     * Stats per sender, the ones with unmatched messages first (most unmatched
     * first), then by volume. Lines that aren't log entries -- including the
     * indented message snippets -- are ignored.
     */
    fun fromLog(logText: String): List<SenderStat> {
        class Acc(var saved: Int = 0, var duplicate: Int = 0, var ignored: Int = 0, var unmatched: Int = 0, var errors: Int = 0)
        val bySender = LinkedHashMap<String, Acc>()
        for (line in logText.lineSequence()) {
            val m = LINE.matchEntire(line.trimEnd()) ?: continue
            val sender = m.groupValues[2].trim().ifEmpty { UNKNOWN_SENDER }
            val outcome = m.groupValues[3]
            val acc = bySender.getOrPut(sender) { Acc() }
            when {
                outcome.startsWith("SAVED") -> acc.saved++
                outcome.startsWith("DUPLICATE") -> acc.duplicate++
                outcome.startsWith("IGNORED") -> acc.ignored++
                outcome.startsWith("NO MATCH") -> acc.unmatched++
                outcome.startsWith("ERROR") -> acc.errors++
            }
        }
        return bySender.map { (sender, a) -> SenderStat(sender, a.saved, a.duplicate, a.ignored, a.unmatched, a.errors) }
            .filter { it.total > 0 }
            .sortedWith(compareByDescending<SenderStat> { it.unmatched }.thenByDescending { it.total }.thenBy { it.sender })
    }

    /** One readable line: "ADCB — 212 saved · 3 unmatched". Zero counts are left out. */
    fun describe(stat: SenderStat): String {
        val parts = buildList {
            if (stat.saved > 0) add("${stat.saved} saved")
            if (stat.duplicate > 0) add("${stat.duplicate} duplicate")
            if (stat.ignored > 0) add("${stat.ignored} ignored")
            if (stat.unmatched > 0) add("${stat.unmatched} unmatched")
            if (stat.errors > 0) add("${stat.errors} failed")
        }
        return "${stat.sender} — ${parts.joinToString(" · ")}"
    }
}
