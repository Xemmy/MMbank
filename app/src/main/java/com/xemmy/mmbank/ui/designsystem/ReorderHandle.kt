package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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

/** Scale a row grows to while it is being dragged. */
const val LIFT_SCALE = 1.02f

/**
 * A grip the user drags up or down to move a row; each [STEP_DP] of travel calls [onMove] with +1 or
 * -1 and ticks the haptics. [onDragStateChange] reports start/end so the row can lift (see
 * [liftWhileDragging]). TalkBack users get "Move up"/"Move down" actions on the grip itself, so lists
 * need no extra buttons. The row keeps its key, so the gesture carries on while the list re-orders.
 */
@Composable
fun ReorderHandle(
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDragStateChange: (Boolean) -> Unit = {},
) {
    var accumulated by remember { mutableFloatStateOf(0f) }
    // The list re-orders (and rebuilds the callbacks) mid-drag, so always call the latest ones.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragState by rememberUpdatedState(onDragStateChange)
    val haptics = LocalHapticFeedback.current
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = "Reorder",
        tint = MMTheme.colors.accent,
        modifier = modifier
            .padding(end = 4.dp)
            .size(40.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { currentOnMove(-1); true },
                    CustomAccessibilityAction("Move down") { currentOnMove(1); true },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnDragState(true)
                    },
                    onDragEnd = { currentOnDragState(false) },
                    onDragCancel = { currentOnDragState(false) },
                    onDrag = { change, amount ->
                        change.consume()
                        val (steps, rest) = reorderSteps(accumulated + amount.y, STEP_DP.dp.toPx())
                        accumulated = rest
                        if (steps != 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnMove(if (steps > 0) 1 else -1)
                        }
                    },
                )
            },
    )
}

/** Lifts a row (slight grow + shadow, animated) while [lifted] is true. */
@Composable
fun Modifier.liftWhileDragging(lifted: Boolean): Modifier {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (lifted) LIFT_SCALE else 1f, label = "liftScale")
    val elevation by androidx.compose.animation.core.animateFloatAsState(if (lifted) 12f else 0f, label = "liftElevation")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        shadowElevation = elevation * density
    }
}
