package com.jothivel.chits.data.local

import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale

/** Due-date maths, the bulk due loader, and the receipt/time overrides used when replaying cloud collections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionScheduleTest {
    private lateinit var db: AppDatabase
    private fun day(text: String) = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).parse(text)!!

    @Before fun setUp() {
        db = TestDb.newDb()
    }

    @After fun tearDown() = db.close()

    private fun dueCount(start: String, asOf: String, months: Int = 6): Int {
        db.clearAllTables()
        TestDb.seedGroup(db, "G", months = months, installmentPaise = 100_00, start = start, subscribers = 1)
        TestDb.seedMember(db, "M")
        TestDb.seedMembership(db, "M", "G")
        return CollectionService.calculateDueBreakdown(db, "M", "G", day(asOf)).payablePaise.toInt() / 100_00
    }

    @Test fun `an installment is due on the start day of its month, not on the 1st`() {
        // A chit starting on the 25th: installment 2 falls due on 25-Feb, so on 1-Feb only #1 is due.
        assertEquals(1, dueCount("25-Jan-2026", "01-Feb-2026"))
        assertEquals(1, dueCount("25-Jan-2026", "24-Feb-2026"))
        assertEquals(2, dueCount("25-Jan-2026", "25-Feb-2026"))
        assertEquals(2, dueCount("25-Jan-2026", "31-Mar-2026".replace("31-Mar", "24-Mar")))
        assertEquals(3, dueCount("25-Jan-2026", "25-Mar-2026"))
    }

    @Test fun `nothing is due before the start date, and everything by the end`() {
        assertEquals(0, dueCount("25-Jan-2026", "24-Jan-2026"))
        assertEquals(1, dueCount("25-Jan-2026", "25-Jan-2026"))
        assertEquals(6, dueCount("25-Jan-2026", "25-Dec-2030"))
    }

    @Test fun `a start date on the 31st falls due on the last day of shorter months`() {
        assertEquals(1, dueCount("31-Jan-2026", "27-Feb-2026"))
        assertEquals(2, dueCount("31-Jan-2026", "28-Feb-2026"))
        assertEquals(3, dueCount("31-Jan-2026", "31-Mar-2026"))
    }

    @Test fun `the overdue day count runs from the real due date`() {
        dueCount("25-Jan-2026", "05-Feb-2026")
        val breakdown = CollectionService.calculateDueBreakdown(db, "M", "G", day("05-Feb-2026"))
        assertEquals(listOf(1), breakdown.pendingInstallments)
        assertEquals(11, breakdown.overdueDays) // 25-Jan -> 05-Feb
    }

    @Test fun `the bulk loader returns exactly what the per-member calculation returns`() {
        TestDb.seedGroup(db, "G1", months = 6, installmentPaise = 100_00, start = "10-Jan-2026", subscribers = 6)
        TestDb.seedGroup(db, "G2", months = 4, installmentPaise = 250_00, start = "20-Feb-2026", subscribers = 4, registerNo = "R2")
        for (m in listOf("A", "B", "C")) {
            TestDb.seedMember(db, m)
            TestDb.seedMembership(db, m, "G1")
        }
        TestDb.seedMember(db, "D")
        TestDb.seedMembership(db, "D", "G2")
        TestDb.seedMembership(db, "A", "G2")
        CollectionService.record(db, "p1", "A", "A", "G1", 150_00, "Cash", null, "", "2026-01-11")
        CollectionService.record(db, "p2", "B", "B", "G1", 700_00, "Cash", null, "", "2026-01-11") // advance credit
        CollectionService.record(db, "p3", "A", "A", "G2", 250_00, "UPI", "U1", "", "2026-02-21")
        val asOf = day("15-Apr-2026")

        val bulk = DueLoader.loadAll(db, asOf)
        assertEquals(5, bulk.size) // A,B,C in G1 + D and A in G2
        for (row in bulk) {
            val single = CollectionService.calculateDueBreakdown(db, row.member.id, row.group.id, asOf)
            assertEquals("${row.member.id}/${row.group.id}", single, row.breakdown)
        }
        val forGroup = DueLoader.loadForGroup(db, "G1", asOf)
        assertEquals(setOf("A", "B", "C"), forGroup.keys)
        assertEquals(CollectionService.calculateDueBreakdown(db, "B", "G1", asOf), forGroup.getValue("B"))
    }

    @Test fun `the bulk loader skips inactive memberships`() {
        TestDb.seedGroup(db, "G1", months = 3, subscribers = 3)
        TestDb.seedMember(db, "A")
        TestDb.seedMember(db, "B")
        TestDb.seedMembership(db, "A", "G1")
        TestDb.seedMembership(db, "B", "G1", active = false)
        assertEquals(listOf("A"), DueLoader.loadAll(db).map { it.member.id })
        assertEquals(setOf("A"), DueLoader.loadForGroup(db, "G1").keys)
    }

    @Test fun `replaying a collection can keep its receipt number and time and skip the activity log`() {
        TestDb.seedGroup(db, "G1", months = 3, subscribers = 3)
        TestDb.seedMember(db, "A")
        TestDb.seedMembership(db, "A", "G1")
        val when1 = 1_700_000_000_000L
        val saved = CollectionService.record(
            db, "cloud-1", "A", "A", "G1", 100_00, "Cash", null, "", "2026-01-05",
            collectedBy = "Kumar", collectedByAgentId = "AG1",
            receiptNoOverride = "JVC-20260105-1200-AB12", paidAtOverride = when1, logActivity = false
        )
        assertEquals("JVC-20260105-1200-AB12", saved.receiptNo)
        val receipt = db.collectionReceiptDao().getByRequestIdSync("cloud-1")!!
        assertEquals(when1, receipt.paidAt)
        assertEquals("JVC-20260105-1200-AB12", receipt.receiptNo)
        val payment = db.paymentDao().getPaymentsByMemberSync("A").single()
        assertEquals(when1, payment.paidAt)
        assertEquals("AG1", payment.collectedByAgentId)
        assertTrue(db.activityLogDao().getByTypeSync("PAYMENT_RECORDED").isEmpty())

        // replaying the same request again is still a no-op
        CollectionService.record(db, "cloud-1", "A", "A", "G1", 100_00, "Cash", null, "", "2026-01-05", receiptNoOverride = "X", paidAtOverride = 1L)
        assertEquals(1, db.paymentDao().getPaymentsByMemberSync("A").size)

        // and the normal path still mints a receipt number and logs the collection
        val normal = CollectionService.record(db, "local-1", "A", "A", "G1", 50_00, "Cash", null, "", "2026-01-06")
        assertTrue(normal.receiptNo.startsWith("JVC-"))
        assertNotNull(db.activityLogDao().getByTypeSync("PAYMENT_RECORDED").singleOrNull())
    }
}
