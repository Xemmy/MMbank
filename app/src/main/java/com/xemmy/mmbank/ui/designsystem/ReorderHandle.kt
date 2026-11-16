package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMTheme

/** How far the finger travels before the row moves one place. */
private const val STEP_DP = 56f

/**
 * Turns an accumulated vertical drag into whole steps: returns how many places to move (negative =
 * up) and the drag left over. Pure so the rule is testable on the JVM.
 */
fun reorderSteps(accumulated: Float, threshold: Float): Pair<Int, Float> {
    if (threshold <= 0f) return 0 to accumulated
    var steps = 0
    var rest = accumulated
    while (rest >= threshold) { rest -= threshold; steps++ }
    while (rest <= -threshold) { rest += threshold; steps-- }
    return steps to rest
}

/**
 * A grip the user drags up or down to move a row; each [STEP_DP] of travel calls [onMove] with +1 or
 * -1. The row keeps its key, so the gesture carries on while the list re-orders under the finger.
 * Lists also keep a menu or buttons for the same move, for people who cannot drag.
 */
@Composable
fun ReorderHandle(onMove: (Int) -> Unit, modifier: Modifier = Modifier) {
    var accumulated by remember { mutableFloatStateOf(0f) }
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = "Drag up or down to reorder",
        tint = MMTheme.colors.textSecondary,
        modifier = modifier
            .size(36.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        val (steps, rest) = reorderSteps(accumulated + amount.y, STEP_DP.dp.toPx())
                        accumulated = rest
                        if (steps != 0) onMove(if (steps > 0) 1 else -1)
                    },
                )
            },
    )
}
