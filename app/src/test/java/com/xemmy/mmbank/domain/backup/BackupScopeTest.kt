package com.xemmy.mmbank.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupScopeTest {

    @Test fun aBackupMadeInDemoModeKeepsTheRealPeople() {
        assertEquals("Mom|Ali", BackupScope.personsRaw(demoMode = true, personsRaw = "Alex Rivera|Sam Carter", demoPersonsBackup = "Mom|Ali"))
        assertEquals("Mom|Ali", BackupScope.personsRaw(demoMode = false, personsRaw = "Mom|Ali", demoPersonsBackup = ""))
    }

    private data class Link(val transactionId: Long?, val labelId: Long)

    @Test fun linksToRowsOutsideTheExportAreDropped() {
        val links = listOf(Link(1, 9), Link(2, 9), Link(900, 9), Link(null, 9))
        val kept = BackupScope.referencingExported(links, exported = setOf(1L, 2L)) { it.transactionId }
        assertEquals(listOf(Link(1, 9), Link(2, 9), Link(null, 9)), kept)
    }

    @Test fun aRealRowOnADemoAccountIsExportedUnassigned() {
        assertEquals(5L, BackupScope.exportedAccountOrNull(5L, setOf(5L, 6L)))
        assertNull(BackupScope.exportedAccountOrNull(77L, setOf(5L, 6L)))
        assertNull(BackupScope.exportedAccountOrNull(null, setOf(5L)))
    }

    @Test fun theCsvShowsTheUsersCategory() {
        val names = mapOf(3L to "Groceries", 4L to "Dining")
        assertEquals("Dining", BackupScope.csvCategory(4L, names))
        assertEquals("", BackupScope.csvCategory(null, names))
        assertEquals("", BackupScope.csvCategory(99L, names))
    }
}
