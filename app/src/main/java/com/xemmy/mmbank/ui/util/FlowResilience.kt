package com.xemmy.mmbank.ui.util

import com.xemmy.mmbank.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Keeps a UI state flow alive across errors. A plain `catch { emit(fallback) }`
 * COMPLETES the flow after the first error — the screen then silently freezes
 * on its last state (balances stop reacting to deletes/edits) until the next
 * resubscription. This instead records the error, shows the fallback, and
 * resubscribes the upstream with capped backoff so live updates resume.
 */
fun <T> Flow<T>.recoverAndRetry(
    tag: String,
    container: AppContainer,
    fallback: (Throwable) -> T?,
): Flow<T> = retryWhen { cause, attempt ->
    container.recordError(tag, cause)
    fallback(cause)?.let { emit(it) }
    delay((2_000L * (attempt + 1)).coerceAtMost(15_000L))
    true
}
