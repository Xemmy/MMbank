package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** Alert card with a tone bar, title, body and optional action buttons. */
@Composable
fun Banner(
    tone: MMTone,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MMTheme.colors
    val label = toneLabel(tone)
    MMCard(
        modifier = modifier.semantics(mergeDescendants = true) { stateDescription = label },
        tone = tone,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (actions != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MMSpacing.sm, Alignment.End),
                content = actions,
            )
        }
    }
}
