package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps the enabled custom rules compiled and in memory, so the capture
 * services don't pay a Room read plus a regex compilation for every single
 * notification posted on the device.
 *
 * Room's invalidation tracker re-emits whenever the parsing_rules table
 * changes — the rule editor, enable/disable toggles and backup import all
 * go through it — so edits still take effect immediately, exactly like the
 * old read-per-message behavior.
 */
class CompiledRuleCache(
    ruleRepository: ParsingRuleRepository,
    scope: CoroutineScope,
) {

    private val compiled: StateFlow<List<CompiledRule>?> = ruleRepository.observeAll()
        .map { rules -> rules.filter { it.enabled }.mapNotNull(RuleCompiler::compile) }
        // A DB hiccup must never kill capture: degrade to built-ins only.
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Current compiled custom rules. Suspends only until the very first DB
     * read completes; afterwards this is a plain in-memory access.
     */
    suspend fun enabledRules(): List<CompiledRule> = compiled.filterNotNull().first()
}
