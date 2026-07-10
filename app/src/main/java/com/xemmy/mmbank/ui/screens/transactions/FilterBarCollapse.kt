package com.xemmy.mmbank.ui.screens.transactions

/**
 * Pure math behind the collapsing filter header on the activity feed: upward
 * scroll compresses the search field + filter chips into a compact top bar,
 * downward scroll past the top of the list expands them back. Kept free of
 * Compose so the consumption/clamping rules are JVM-testable; the screen's
 * NestedScrollConnection delegates its deltas here.
 *
 * Sign convention matches NestedScrollConnection: negative y = content
 * scrolling up (collapse), positive y = scrolling down (expand). Both entry
 * points return the vertical delta they consumed, same sign as the input.
 */
class FilterBarCollapse(initialRangePx: Float = 0f) {

    /** Total collapsible height in px; set from the measured header. */
    var rangePx: Float = initialRangePx.coerceAtLeast(0f)
        private set

    /** How many px of the header are currently hidden, in 0..rangePx. */
    var offsetPx: Float = 0f
        private set

    /** 0 = fully expanded, 1 = fully collapsed into the compact bar. */
    val fraction: Float
        get() = if (rangePx <= 0f) 0f else (offsetPx / rangePx).coerceIn(0f, 1f)

    /** Adopts the header's measured height, re-clamping the current offset. */
    fun updateRange(newRangePx: Float) {
        rangePx = newRangePx.coerceAtLeast(0f)
        offsetPx = offsetPx.coerceIn(0f, rangePx)
    }

    /**
     * Pre-scroll: upward deltas collapse the header before the list moves,
     * so the header compresses first. Downward deltas are left untouched.
     */
    fun onPreScroll(deltaY: Float): Float {
        if (deltaY >= 0f) return 0f
        val previous = offsetPx
        offsetPx = (offsetPx - deltaY).coerceAtMost(rangePx)
        return -(offsetPx - previous)
    }

    /**
     * Post-scroll: downward deltas the list could not use (it's already at
     * the top) expand the header back. Upward deltas are left untouched.
     */
    fun onPostScroll(deltaY: Float): Float {
        if (deltaY <= 0f) return 0f
        val previous = offsetPx
        offsetPx = (offsetPx - deltaY).coerceAtLeast(0f)
        return -(offsetPx - previous)
    }

    /** Snaps fully open (e.g. when the user taps the compact bar). */
    fun expand() {
        offsetPx = 0f
    }
}
