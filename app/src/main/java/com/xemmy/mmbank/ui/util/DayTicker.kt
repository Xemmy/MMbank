package com.xemmy.mmbank.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Milliseconds from [nowMillis] until the next local midnight in [zone].
 * Uses the zone's own start-of-next-day so DST days (23/25h) are handled.
 * Always at least 1 so a ticker loop can never spin.
 */
fun millisUntilNextMidnight(nowMillis: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val next = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return (next - nowMillis).coerceAtLeast(1L)
}

/**
 * Today's date that stays correct while the screen is open: re-evaluated at
 * every midnight and whenever the app resumes (the timer may not run while
 * the process is frozen). Replaces `remember { LocalDate.now() }`.
 */
@Composable
fun rememberCurrentDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now(zone)) }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now(zone)
                resumeTick++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(today, resumeTick) {
        delay(millisUntilNextMidnight(System.currentTimeMillis(), zone))
        today = LocalDate.now(zone)
    }
    return today
}
