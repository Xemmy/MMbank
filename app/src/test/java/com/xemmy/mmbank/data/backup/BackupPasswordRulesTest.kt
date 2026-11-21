package com.xemmy.mmbank.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPasswordRulesTest {
    @Test fun `export needs a matching long password`() {
        assertTrue(BackupPasswordRules.canExport("secret1", "secret1"))
        assertFalse(BackupPasswordRules.canExport("secret1", "secret2"))
        assertFalse(BackupPasswordRules.canExport("secret1", ""))
        assertFalse(BackupPasswordRules.canExport("abc", "abc"))
    }

    @Test fun `import only needs a long enough password`() {
        assertTrue(BackupPasswordRules.canImport("secret1"))
        assertFalse(BackupPasswordRules.canImport("abc"))
    }

    @Test fun `mismatch only shows once confirm is typed`() {
        assertFalse(BackupPasswordRules.mismatch("secret1", ""))
        assertTrue(BackupPasswordRules.mismatch("secret1", "sec"))
        assertFalse(BackupPasswordRules.mismatch("secret1", "secret1"))
    }
}
