package com.xemmy.mmbank.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockTimeoutLabelTest {

    @Test
    fun `sub-hour values render in minutes`() {
        assertEquals("1 min", appLockTimeoutLabel(1))
        assertEquals("15 min", appLockTimeoutLabel(15))
        assertEquals("45 min", appLockTimeoutLabel(45))
    }

    @Test
    fun `whole-hour values render in hours`() {
        assertEquals("1 hr", appLockTimeoutLabel(60))
        assertEquals("6 hr", appLockTimeoutLabel(360))
        assertEquals("12 hr", appLockTimeoutLabel(720))
    }

    @Test
    fun `non-whole-hour values above an hour stay in minutes`() {
        assertEquals("90 min", appLockTimeoutLabel(90))
    }

    @Test
    fun `every offered option has a label`() {
        assertEquals(
            listOf("1 min", "2 min", "5 min", "15 min", "1 hr", "6 hr", "12 hr"),
            APP_LOCK_TIMEOUT_OPTIONS_MIN.map(::appLockTimeoutLabel),
        )
    }
}
