package com.xemmy.mmbank.ui.screens.subscriptions

import com.xemmy.mmbank.domain.repository.RescanCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanMessageTest {
    @Test fun `all three counts are listed`() {
        assertEquals("Found 2 new · 1 updated · 1 removed", scanMessage(RescanCounts(2, 1, 1)))
    }

    @Test fun `zero counts are left out`() {
        assertEquals("1 removed", scanMessage(RescanCounts(0, 0, 1)))
    }

    @Test fun `no change says so`() {
        assertEquals("Nothing changed", scanMessage(RescanCounts(0, 0, 0)))
    }
}
