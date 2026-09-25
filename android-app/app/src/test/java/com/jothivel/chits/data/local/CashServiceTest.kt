package com.jothivel.chits.data.local

import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CashServiceTest {
    private lateinit var db: AppDatabase
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    @Before fun setUp() {
        db = TestDb.newDb()
        TestDb.seedGroup(db, "G1", months = 12, installmentPaise = 1000_00, start = "01-Jan-2026", subscribers = 12)
        for (id in listOf("M1", "M2", "M3")) {
            TestDb.seedMember(db, id)
            TestDb.seedMembership(db, id, "G1")
        }
    }

    @After fun tearDown() = db.close()

    private fun collect(req: String, member: String, rupees: Long, mode: String = "Cash", agentId: String? = null, agentName: String? = null) =
        CollectionService.record(db, req, member, member, "G1", rupees * 100, mode, if (mode == "Cash") null else "UTR-$req", "", today, agentName, agentId)

    @Test fun `agent cash collected is tracked per agent and only cash counts`() {
        collect("a1", "M1", 300, agentId = "AG1", agentName = "Kumar")
        collect("a2", "M2", 200, agentId = "AG1", agentName = "Kumar")
        collect("a3", "M3", 500, mode = "UPI", agentId = "AG1", agentName = "Kumar")
        collect("b1", "M1", 100, agentId = "AG2", agentName = "Devi")
        collect("o1", "M2", 900) // collected by the office, not an agent

        val summaries = CashService.agentSummaries(db).associateBy { it.agentId }
        assertEquals(2, summaries.size)
        assertEquals(500_00L, summaries.getValue("AG1").collectedPaise)
        assertEquals("Kumar", summaries.getValue("AG1").agentName)
        assertEquals(500_00L, summaries.getValue("AG1").outstandingPaise)
        assertEquals(100_00L, summaries.getValue("AG2").collectedPaise)
    }

    @Test fun `a handover reduces what the agent still holds and cannot exceed it`() {
        collect("a1", "M1", 500, agentId = "AG1", agentName = "Kumar")
        val id = CashService.recordHandover(db, "h1", "AG1", "Kumar", 300_00, "evening")
        assertEquals(200_00L, CashService.outstandingPaise(db, "AG1"))

        val error = assertThrows(IllegalArgumentException::class.java) { CashService.recordHandover(db, "h2", "AG1", "Kumar", 250_00, "") }
        assertTrue(error.message!!.contains("₹200"))
        assertThrows(IllegalArgumentException::class.java) { CashService.recordHandover(db, "h3", "AG1", "Kumar", 0, "") }
        assertThrows(IllegalArgumentException::class.java) { CashService.recordHandover(db, "h4", "", "x", 1_00, "") }

        // idempotent on the request id: a double tap does not hand over twice
        assertEquals(id, CashService.recordHandover(db, "h1", "AG1", "Kumar", 300_00, "evening"))
        assertEquals(200_00L, CashService.outstandingPaise(db, "AG1"))
        assertEquals(1, db.cashHandoverDao().getAllSync().size)
    }

    @Test fun `a wrong handover is reversed with a reason and the cash is outstanding again`() {
        collect("a1", "M1", 500, agentId = "AG1", agentName = "Kumar")
        val id = CashService.recordHandover(db, "h1", "AG1", "Kumar", 500_00, "")
        assertEquals(0L, CashService.outstandingPaise(db, "AG1"))
        assertThrows(IllegalArgumentException::class.java) { CashService.reverseHandover(db, id, "no") }
        CashService.reverseHandover(db, id, "entered on wrong day")
        assertEquals(500_00L, CashService.outstandingPaise(db, "AG1"))
        assertEquals("REVERSED", db.cashHandoverDao().getAllSync().single().status)
        assertThrows(IllegalStateException::class.java) { CashService.reverseHandover(db, id, "again please") }
    }

    @Test fun `the daily summary splits collections by mode and payouts by type`() {
        collect("o1", "M1", 400)                                            // office cash
        collect("a1", "M2", 300, agentId = "AG1", agentName = "Kumar")      // agent cash
        collect("u1", "M3", 250, mode = "UPI")                              // UPI
        CashService.recordHandover(db, "h1", "AG1", "Kumar", 100_00, "")    // 100 reaches the office
        ChitAdminService.setWinner(db, "G1", 1, "M1", 500_00, "01-Jan-2026")
        FinancialService.record(db, "d1", "DELIVERY", "M1", "G1", 150_00, "Cash", null, "")
        FinancialService.record(db, "s1", "SETTLEMENT", "M2", "G1", 70_00, "UPI", "UTR-9", "")

        val s = CashService.dailySummary(db, today)
        assertEquals(400_00L, s.directCashPaise)
        assertEquals(100_00L, s.agentHandoverPaise)
        assertEquals(250_00L, s.upiPaise)
        assertEquals(700_00L, s.allCashCollectedPaise)
        assertEquals(150_00L, s.deliveryPaise)
        assertEquals(70_00L, s.settlementPaise)
    }

    @Test fun `imported history never counts as cash in the drawer`() {
        val now = System.currentTimeMillis()
        db.paymentDao().insertPayment(com.jothivel.chits.data.local.entity.PaymentEntity().apply {
            id = "imp"; memberId = "M1"; groupId = "G1"; installmentId = "1"; amountPaid = 999_00; mode = "CASH"
            receiptNo = "IMP-20260101-ABCD"; paidAt = now; status = "PAID"
        })
        assertEquals(0L, CashService.dailySummary(db, today).directCashPaise)
    }

    @Test fun `an unreadable date is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CashService.dailySummary(db, "31/12/2026") }
    }
}
