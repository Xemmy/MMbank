package com.xemmy.mmbank.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogKindTest {
    @Test
    fun `confirm dialogs use the primary button`() {
        assertEquals(ConfirmButtonRole.PRIMARY, DialogKind.CONFIRM.confirmRole())
    }

    @Test
    fun `destructive dialogs use the danger button`() {
        assertEquals(ConfirmButtonRole.DANGER, DialogKind.DESTRUCTIVE.confirmRole())
    }
}
