package com.xemmy.mmbank.ui.screens.rules

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind

/**
 * Builds the rule the editor saves. Editing starts from the stored rule so
 * fields the editor does not expose (`enabled`, `createdAt`, `isDemo`) are
 * preserved -- rebuilding from scratch silently re-enabled a disabled rule
 * and an unreviewed AI candidate on every Save.
 */
object RuleEditorMerge {
    fun apply(
        original: ParsingRule?,
        id: Long,
        name: String,
        pattern: String,
        senderPattern: String?,
        direction: TransactionDirection,
        defaultCurrency: String?,
        priority: Int,
        sampleText: String?,
        assignAccountId: Long?,
        assignKind: TransactionKind?,
    ): ParsingRule {
        val base = original ?: ParsingRule(name = name, pattern = pattern, direction = direction)
        return base.copy(
            id = id,
            name = name,
            pattern = pattern,
            senderPattern = senderPattern,
            direction = direction,
            defaultCurrency = defaultCurrency,
            priority = priority,
            sampleText = sampleText,
            assignAccountId = assignAccountId,
            assignKind = assignKind,
        )
    }
}
