package com.xemmy.mmbank.ui.screens.insights

import com.xemmy.mmbank.domain.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NetTileKindTest {
    @Test
    fun `negative net reads as money out`() {
        assertEquals(TransactionKind.SPEND, netTileKind(-0.01))
    }

    @Test
    fun `zero and positive net read as money in`() {
        assertEquals(TransactionKind.INCOME, netTileKind(0.0))
        assertEquals(TransactionKind.INCOME, netTileKind(125.5))
    }
}
