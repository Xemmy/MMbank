package com.xemmy.mmbank.domain.ai

/**
 * Caps how many automatic Gate-2 escalations (see [AiForwardGate] /
 * IngestCapturedMessageUseCase.onGate1Miss) may fire per local day, so a
 * burst of unmatched messages can't spam the AI engine with unlimited
 * unattended requests -- on-device load in LOCAL mode, real cost in ONLINE
 * mode. Deliberately scoped to the automatic path only: the manual
 * "Parse with AI" action and the Settings test buttons are user-initiated
 * one-offs and stay uncapped.
 *
 * Pure state-transition logic, decoupled from persistence (AppPreferences)
 * and from wall-clock day boundaries (the day key is caller-supplied, e.g.
 * "yyyy-MM-dd" in local time) so it's JVM-testable without Robolectric.
 */
object AiDailyBudget {

    /** [dayKey] is an opaque caller-defined day identifier; [count] is requests consumed so far that day. */
    data class State(val dayKey: String, val count: Int)

    /**
     * True when [state] already used up [limit] for [today]. A state left
     * over from a previous day never counts as exhausted -- it's stale, and
     * [next] rolls it into a fresh count for today.
     */
    fun isExhausted(state: State?, today: String, limit: Int): Boolean {
        if (state == null || state.dayKey != today) return false
        return state.count >= limit
    }

    /** The state after consuming one more request for [today]. Rolls a stale/absent state over to count 1. */
    fun next(state: State?, today: String): State =
        if (state != null && state.dayKey == today) {
            state.copy(count = state.count + 1)
        } else {
            State(dayKey = today, count = 1)
        }
}
