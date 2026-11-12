package com.xemmy.mmbank.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGroupsTest {
    @Test
    fun everyEntryAppearsInExactlyOneGroup() {
        val placed = SettingsGroup.entries.flatMap { it.entries }
        assertEquals(SettingsEntry.entries.toSet(), placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test
    fun routesAreUnique() {
        val routes = SettingsEntry.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun noEmptyGroups() {
        assertTrue(SettingsGroup.entries.all { it.entries.isNotEmpty() })
    }

    @Test
    fun groupOrderMatchesPlan() {
        assertEquals(
            listOf("Capture", "Money", "Notifications", "Appearance", "Security & privacy", "Data", "Connections", "About"),
            SettingsGroup.entries.map { it.title },
        )
    }

    @Test
    fun notificationsHaveTheirOwnGroupAndRoute() {
        assertEquals("settings_notifications", SettingsEntry.NOTIFICATIONS.route)
        assertEquals(listOf(SettingsEntry.NOTIFICATIONS), SettingsGroup.NOTIFICATIONS.entries)
    }

    @Test
    fun notificationsSummaryCountsTheSwitchesThatAreOn() {
        assertEquals("All off", notificationsSummary(listOf(false, false, false, false)))
        assertEquals("1 of 4 on", notificationsSummary(listOf(true, false, false, false)))
        assertEquals("4 of 4 on", notificationsSummary(listOf(true, true, true, true)))
        assertEquals("All off", notificationsSummary(emptyList()))
    }
}
