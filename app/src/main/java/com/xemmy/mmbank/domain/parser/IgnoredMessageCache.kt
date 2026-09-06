package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.repository.IgnoredMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps the ignore list in memory, for the same reason [CompiledRuleCache]
 * keeps the rules there: [shouldIgnore] is consulted for every digit-bearing
 * notification posted on the device, and a Room read per notification would be
 * a real battery cost for a list that changes only when the user edits it.
 *
 * Room's invalidation tracker re-emits on every change to
 * `ignored_message_patterns`, so adding or deleting a pattern takes effect on
 * the very next message.
 */
class IgnoredMessageCache(
    repository: IgnoredMessageRepository,
    scope: CoroutineScope,
) {

    private val patterns: StateFlow<List<IgnoredMessagePattern>?> = repository.observeAll()
        // A DB hiccup must never kill capture: an empty list just means
        // nothing is ignored, which is the safe direction to fail in.
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True when [text] is another copy of a template the user muted. The
     * stored fingerprint is compared first because it settles the common case
     * -- the same template again, only the numbers changed -- on a string
     * equality, leaving the costlier token overlap
     * ([MessageSimilarity.isSimilar]) for the near-misses.
     */
    suspend fun shouldIgnore(text: String): Boolean {
        val stored = patterns.filterNotNull().first()
        if (stored.isEmpty()) return false
        val print = MessageSimilarity.fingerprint(text)
        if (print.isEmpty()) return false
        if (stored.any { it.fingerprint == print }) return true
        return stored.any { MessageSimilarity.isSimilar(text, it.sample) }
    }
}
