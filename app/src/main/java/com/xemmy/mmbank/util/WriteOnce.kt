package com.xemmy.mmbank.util

/**
 * Ignores a second tap on a button whose write is already running or already done. A button
 * that writes (record an adjustment, mark a bill paid) stays on screen until the ledger flow
 * recomputes, so a quick double tap used to write twice: two balance adjustments, or a bill
 * advanced two months. Each action gets a key that names what it writes (the account and
 * moment, the bill and its cycle); a key is claimed once and released only if the write fails,
 * so it can be retried. Thread-safe; pure JVM.
 */
class WriteOnce<K> {
    private val claimed = java.util.Collections.synchronizedSet(HashSet<K>())

    /** True when [key] was free and is now claimed; false for a repeat. */
    fun claim(key: K): Boolean = claimed.add(key)

    /** Frees [key] after a failed write, so the user can try again. */
    fun release(key: K) {
        claimed.remove(key)
    }
}
