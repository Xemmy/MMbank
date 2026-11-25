package com.xemmy.mmbank.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFormatTest {
    @Test fun storedValueParsesLeniently() {
        assertEquals(ExportFormat.JSON, ExportFormat.fromStored("JSON"))
        assertEquals(ExportFormat.CSV, ExportFormat.fromStored("CSV"))
        assertEquals(ExportFormat.CSV, ExportFormat.fromStored(""))
        assertEquals(ExportFormat.CSV, ExportFormat.fromStored(null))
        assertEquals(ExportFormat.CSV, ExportFormat.fromStored("xml"))
    }

    @Test fun fileNameUsesTheExtension() {
        assertEquals("mmbank-activity-20261125-1030.csv", ExportFormat.CSV.fileName("20261125-1030"))
        assertEquals("mmbank-activity-20261125-1030.json", ExportFormat.JSON.fileName("20261125-1030"))
    }
}
