package com.xemmy.mmbank.data.backup

import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupEntity
import com.xemmy.mmbank.data.local.entity.PayLaterInstallmentEntity
import com.xemmy.mmbank.data.local.entity.PayLaterPlanEntity
import com.xemmy.mmbank.data.local.entity.SubscriptionEntity
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.SplitType
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupExtrasTest {
    @Test fun `bill round trips`() {
        val e = BillEntity(3, "Rent", BigDecimal("4500.50"), "AED", BillRecurrence.MONTHLY, 1_700_000_000_000, 5, 31)
        assertEquals(e, e.toBackup().toEntity())
        val oneOff = e.copy(recurrence = null)
        assertEquals(oneOff, oneOff.toBackup().toEntity())
    }

    @Test fun `pay later plan and installment round trip`() {
        val plan = PayLaterPlanEntity(
            id = 3, merchant = "Carrefour", provider = "Tabby", totalAmount = BigDecimal("327.32"), currencyCode = "AED",
            installmentCount = 4, cadence = PayLaterCadence.BIWEEKLY, payFromAccountId = 1, payToAccountId = 2,
            sourceTransactionId = 99, categoryId = 7, note = "groceries", createdAt = 5, isDemo = true,
        )
        assertEquals(plan, plan.toBackup().toEntity())
        val bare = plan.copy(payFromAccountId = null, payToAccountId = null, sourceTransactionId = null, categoryId = null, note = null)
        assertEquals(bare, bare.toBackup().toEntity())
        val inst = PayLaterInstallmentEntity(8, 3, 2, BigDecimal("81.83"), 1_700_000_000_000, 1_700_000_100_000, 55)
        assertEquals(inst, inst.toBackup().toEntity())
        val unpaid = inst.copy(paidAtMillis = null, paymentTransactionId = null)
        assertEquals(unpaid, unpaid.toBackup().toEntity())
    }

    @Test fun `manual subscription round trips and keeps its manual flag`() {
        val e = SubscriptionEntity(
            id = 4, merchant = "Gym", averageAmount = BigDecimal("150.00"), currencyCode = "AED",
            cadence = SubscriptionCadence.YEARLY, nextDueDateMillis = 1_700_000_000_000, lastSeenMillis = 5,
            occurrenceCount = 2, dismissed = true, previousAmount = null, isManual = true,
        )
        assertEquals(e, e.toBackup().toEntity())
    }

    @Test fun `an unknown cadence in a backup reads back as monthly`() {
        val b = BackupManualSubscription(1, "X", "1.00", "AED", "FORTNIGHTLY", 0, 0, 0)
        assertEquals(SubscriptionCadence.MONTHLY, b.toEntity().cadence)
    }

    @Test fun `an old backup without subscription links restores to empty`() {
        val extras = Json { ignoreUnknownKeys = true }.decodeFromString<BackupExtras>("{}")
        assertEquals(emptyList<BackupManualSubscription>(), extras.manualSubscriptions)
        assertEquals(emptyList<BackupSubscriptionLink>(), extras.subscriptionLinks)
    }

    @Test fun `an old backup without pay later restores to empty`() {
        val extras = Json { ignoreUnknownKeys = true }.decodeFromString<BackupExtras>("{}")
        assertEquals(emptyList<BackupPayLaterPlan>(), extras.payLaterPlans)
        assertEquals(emptyList<BackupPayLaterInstallment>(), extras.payLaterInstallments)
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
