package com.xemmy.mmbank.data.backup

import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupEntity
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.SplitType
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupExtrasTest {
    @Test fun `bill round trips`() {
        val e = BillEntity(3, "Rent", BigDecimal("4500.50"), "AED", BillRecurrence.MONTHLY, 1_700_000_000_000, 5)
        assertEquals(e, e.toBackup().toEntity())
        val oneOff = e.copy(recurrence = null)
        assertEquals(oneOff, oneOff.toBackup().toEntity())
    }

    @Test fun `goal and friend round trip`() {
        val g = SavingsGoalEntity(2, "Car", BigDecimal("30000"), BigDecimal("1200.25"), 99L, "🚗", 7, true)
        assertEquals(g, g.toBackup().toEntity())
        val f = FriendEntity(4, "Sam", 8, true)
        assertEquals(f, f.toBackup().toEntity())
    }

    @Test fun `group expense and split round trip`() {
        val grp = SplitGroupEntity(1, "Trip", "✈", 9, false)
        assertEquals(grp, grp.toBackup().toEntity())
        val ex = GroupExpenseEntity(5, 1, "Dinner", BigDecimal("120.00"), "AED", null, SplitType.EXACT, 10)
        assertEquals(ex, ex.toBackup().toEntity())
        val sp = ExpenseSplitEntity(6, 5, 4, BigDecimal("40.00"))
        assertEquals(sp, sp.toBackup().toEntity())
    }

    @Test fun `old backup without extras decodes with null extras`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString<BackupPayload>(
            """{"formatVersion":10,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"0",
            "accounts":[],"transactions":[],"rules":[],"senderLabels":[]}""",
        )
        assertNull(decoded.extras)
    }

    @Test fun `extras survive json`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val extras = BackupExtras(
            friends = listOf(BackupFriend(1, "Sam", 2, false)),
            monthlySpendBudget = 5000.0,
        )
        assertEquals(extras, json.decodeFromString<BackupExtras>(json.encodeToString(extras)))
    }
}
