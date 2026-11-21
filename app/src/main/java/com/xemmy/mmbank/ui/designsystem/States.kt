package com.xemmy.mmbank.ui.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MMbankTheme
import com.xemmy.mmbank.ui.util.rememberReduceMotion

/** Empty list / no data: icon, title, optional body and a primary action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MMTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(MMSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MMSpacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            ) { Text(actionLabel) }
        }
    }
}

/** Failure with a retry action. Uses the danger colour for the icon only; the text stays readable. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null,
) {
    val colors = MMTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(MMSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MMSpacing.md),
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

/**
 * Skeleton rows sized like a real list row (avatar + two text lines). Pulses
 * gently unless the user turned system animations off.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    rows: Int = 6,
) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "loading")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "pulse",
    )
    val alpha = if (reduceMotion) 1f else pulse
    val block = MMTheme.colors.surfaceRaised
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MMSpacing.gutter)
            .semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(MMSpacing.md),
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MMSpacing.md),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(block))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MMSpacing.sm)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
                }
            }
        }
    }
}

@MMPreviews
@Composable
private fun StatesPreview() {
    MMbankTheme {
        Column(Modifier.background(MMTheme.colors.canvas), verticalArrangement = Arrangement.spacedBy(MMSpacing.lg)) {
            EmptyState(title = "No transactions yet", body = "Add one or enable SMS capture.", actionLabel = "Add transaction", onAction = {})
            ErrorState(message = "Couldn't load your accounts.", onRetry = {})
            LoadingState(rows = 2)
        }
    }
}
