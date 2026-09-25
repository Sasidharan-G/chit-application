package com.jothivel.chits.data.local

import com.jothivel.chits.data.models.ChitTemplate
import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChitAdminServiceTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = TestDb.newDb()
        TestDb.seedGroup(db, "G1", months = 3, installmentPaise = 100_00, start = "01-Jan-2026", subscribers = 3)
        for (id in listOf("M1", "M2", "M3")) {
            TestDb.seedMember(db, id)
            TestDb.seedMembership(db, id, "G1")
        }
    }

    @After fun tearDown() = db.close()

    private fun asOf(text: String): Date = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).parse(text)!!

    // ── installment table ───────────────────────────────────────────────────────────────

    @Test fun `flat installments sum to exactly the chit value with the remainder in the last one`() {
        // 100000 paise over 7 months: 14285 each = 99995, remainder 5 goes into month 7.
        val rows = ChitAdminService.buildInstallments("X", 100_000, 7, null)
        assertEquals(7, rows.size)
        assertEquals(100_000L, rows.sumOf { it.baseAmount.toLong() })
        assertTrue(rows.take(6).all { it.baseAmount == 14_285 })
        assertEquals(14_290, rows.last().baseAmount)
        assertTrue(rows.all { it.kasaruAmount == 0 && it.status == "UPCOMING" && it.winningMemberId == null })
    }

    @Test fun `an evenly divisible chit has identical installments`() {
        val rows = ChitAdminService.buildInstallments("X", 300_00, 3, null)
        assertEquals(listOf(100_00, 100_00, 100_00), rows.map { it.baseAmount })
    }

    @Test fun `fixed schedule rows store the gross amount and kasaru in paise`() {
        val plan = ChitTemplate.PLAN_1L.fixedSchedule!!
        val rows = ChitAdminService.buildInstallments("X", 100_000 * 100, 20, plan)
        assertEquals((plan[1].baseAmount + plan[1].kasaruAmount) * 100, rows[1].baseAmount)
        assertEquals(plan[1].kasaruAmount * 100, rows[1].kasaruAmount)
        assertEquals(plan[1].payoutAmount * 100, rows[1].payoutAmount)
        assertNull("month 1 has no payout", rows[0].payoutAmount)
    }

    @Test fun `the last installment remainder is really collected from a member`() {
        // 100000 paise / 7 months on its own chit: every member must end up owing the full value.
        TestDb.seedGroup(db, "G7", months = 7, installmentPaise = 1, start = "01-Jan-2026")
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("G7")!!.apply { chitValue = 100_000 })
        db.installmentDao().insertAll(ChitAdminService.buildInstallments("G7", 100_000, 7, null))
        TestDb.seedMember(db, "M7")
        TestDb.seedMembership(db, "M7", "G7")
        val due = CollectionService.calculateDueBreakdown(db, "M7", "G7", asOf("15-Dec-2026"))
        assertEquals(100_000L, due.payablePaise)
    }

    // ── editing ─────────────────────────────────────────────────────────────────────────

    @Test fun `editing a chit updates its details and writes an audit entry`() {
        ChitAdminService.updateGroupDetails(db, "G1", " New Name ", " NEW-1 ", "North", "01-Jan-2026", 5)
        val g = db.groupDao().getGroupByIdSync("G1")!!
        assertEquals("New Name", g.name)
        assertEquals("NEW-1", g.registerNo)
        assertEquals("North", g.branch)
        assertEquals(5, g.subscriberCount)
        assertTrue(db.activityLogDao().getByTypeSync("GROUP_EDITED").isNotEmpty())
    }

    @Test fun `editing rejects blank fields, a duplicate chit number and a size below the active members`() {
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.updateGroupDetails(db, "G1", "", "R", "B", "01-Jan-2026", 3) }
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.updateGroupDetails(db, "G1", "N", "R", "B", "1/1/2026", 3) }
        TestDb.seedGroup(db, "G2", registerNo = "TAKEN", months = 2)
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.updateGroupDetails(db, "G1", "N", "taken", "B", "01-Jan-2026", 3) }
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.updateGroupDetails(db, "G1", "N", "R", "B", "01-Jan-2026", 2) }
        // keeping its own number is fine
        ChitAdminService.updateGroupDetails(db, "G1", "N", "REG-G1", "B", "01-Jan-2026", 3)
    }

    @Test fun `the start date is locked once a payment exists`() {
        ChitAdminService.updateGroupDetails(db, "G1", "N", "REG-G1", "B", "15-Jan-2026", 3) // no payments yet: allowed
        CollectionService.record(db, "req1", "M1", "Member M1", "G1", 100_00, "Cash", null, "", "2026-01-20")
        assertThrows(IllegalStateException::class.java) { ChitAdminService.updateGroupDetails(db, "G1", "N", "REG-G1", "B", "20-Jan-2026", 3) }
        ChitAdminService.updateGroupDetails(db, "G1", "Renamed", "REG-G1", "B", "15-Jan-2026", 3) // unchanged date: allowed
    }

    // ── closing ─────────────────────────────────────────────────────────────────────────

    @Test fun `a chit with open dues cannot be closed unless forced`() {
        val pending = ChitAdminService.membersWithPendingDues(db, "G1")
        assertEquals(3, pending)
        val error = assertThrows(IllegalStateException::class.java) { ChitAdminService.closeGroup(db, "G1") }
        assertTrue(error.message!!.contains("3 member"))
        assertEquals("ACTIVE", db.groupDao().getGroupByIdSync("G1")!!.status)

        assertEquals(3, ChitAdminService.closeGroup(db, "G1", force = true))
        assertEquals("COMPLETED", db.groupDao().getGroupByIdSync("G1")!!.status)
        assertThrows(IllegalStateException::class.java) { ChitAdminService.closeGroup(db, "G1", force = true) }
    }

    @Test fun `a fully paid chit closes cleanly and can be reopened`() {
        // Far-future asOf is not needed: pay everything that is due today and beyond.
        for (m in listOf("M1", "M2", "M3")) CollectionService.record(db, "r-$m", m, m, "G1", 300_00, "Cash", null, "", "2026-01-02")
        assertEquals(0, ChitAdminService.closeGroup(db, "G1"))
        assertEquals("COMPLETED", db.groupDao().getGroupByIdSync("G1")!!.status)
        ChitAdminService.reopenGroup(db, "G1")
        assertEquals("ACTIVE", db.groupDao().getGroupByIdSync("G1")!!.status)
        assertThrows(IllegalStateException::class.java) { ChitAdminService.reopenGroup(db, "G1") }
    }

    // ── members leaving / rejoining ─────────────────────────────────────────────────────

    @Test fun `a member can leave a chit and rejoin, keeping their history`() {
        CollectionService.record(db, "r1", "M1", "Member M1", "G1", 100_00, "Cash", null, "", "2026-01-02")
        ChitAdminService.deactivateMember(db, "M1", "G1")
        assertFalse(db.membershipDao().getSync("M1", "G1")!!.isActive)
        assertEquals(2, db.membershipDao().countActiveForGroupSync("G1"))
        assertEquals(1, db.paymentDao().getPaymentsByMemberAndGroupSync("M1", "G1").size)
        assertThrows(IllegalStateException::class.java) { ChitAdminService.deactivateMember(db, "M1", "G1") }
        // leaving frees the seat, and collections against the inactive membership are refused
        assertThrows(IllegalArgumentException::class.java) { CollectionService.record(db, "r2", "M1", "M1", "G1", 10_00, "Cash", null, "", "2026-01-03") }

        ChitAdminService.reactivateMember(db, "M1", "G1")
        assertTrue(db.membershipDao().getSync("M1", "G1")!!.isActive)
        assertThrows(IllegalStateException::class.java) { ChitAdminService.reactivateMember(db, "M1", "G1") }
    }

    @Test fun `a member cannot rejoin a full chit`() {
        ChitAdminService.deactivateMember(db, "M1", "G1")
        TestDb.seedMember(db, "M4")
        TestDb.seedMembership(db, "M4", "G1") // takes the freed seat
        val error = assertThrows(IllegalStateException::class.java) { ChitAdminService.reactivateMember(db, "M1", "G1") }
        assertTrue(error.message!!.contains("full"))
    }

    // ── winners ─────────────────────────────────────────────────────────────────────────

    @Test fun `each installment has one winner and a member wins once`() {
        ChitAdminService.setWinner(db, "G1", 2, "M1", 250_00, "15-Feb-2026")
        val inst = db.installmentDao().getByNumberSync("G1", 2)!!
        assertEquals("M1", inst.winningMemberId)
        assertEquals(250_00, inst.payoutAmount)
        assertEquals("AUCTION_DONE", inst.status)
        assertEquals("15-Feb-2026", inst.auctionDate)

        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 2, "M2", 250_00, "15-Feb-2026") } // installment taken
        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 3, "M1", 250_00, "15-Mar-2026") } // already won
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.setWinner(db, "G1", 3, "M2", 0, "15-Mar-2026") }
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.setWinner(db, "G1", 3, "M2", 250_00, "2026-03-15") }
        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 9, "M2", 250_00, "15-Mar-2026") } // no such installment
    }

    @Test fun `a winner must be an active member of an active chit`() {
        ChitAdminService.deactivateMember(db, "M3", "G1")
        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 1, "M3", 100_00, "01-Jan-2026") }
        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 1, "NOBODY", 100_00, "01-Jan-2026") }
        db.groupDao().updateStatus("G1", "COMPLETED")
        assertThrows(IllegalStateException::class.java) { ChitAdminService.setWinner(db, "G1", 1, "M1", 100_00, "01-Jan-2026") }
    }

    @Test fun `a member who won cannot be removed from the chit`() {
        ChitAdminService.setWinner(db, "G1", 1, "M1", 100_00, "01-Jan-2026")
        assertThrows(IllegalStateException::class.java) { ChitAdminService.deactivateMember(db, "M1", "G1") }
    }

    @Test fun `a winner can be cleared with a reason until prize money is delivered`() {
        ChitAdminService.setWinner(db, "G1", 1, "M1", 100_00, "01-Jan-2026")
        assertThrows(IllegalArgumentException::class.java) { ChitAdminService.clearWinner(db, "G1", 1, "no") }
        FinancialService.record(db, "d1", "DELIVERY", "M1", "G1", 40_00, "Cash", null, "")
        val error = assertThrows(IllegalStateException::class.java) { ChitAdminService.clearWinner(db, "G1", 1, "entered by mistake") }
        assertTrue(error.message!!.contains("delivered"))

        val ledger = db.financialTransactionDao().getAllSync().single()
        FinancialService.reverse(db, ledger.id, "wrong entry")
        ChitAdminService.clearWinner(db, "G1", 1, "entered by mistake")
        val inst = db.installmentDao().getByNumberSync("G1", 1)!!
        assertNull(inst.winningMemberId)
        assertEquals("UPCOMING", inst.status)
        ChitAdminService.setWinner(db, "G1", 1, "M2", 100_00, "01-Jan-2026") // free again
    }

    // ── delivery is tied to the winner ──────────────────────────────────────────────────

    @Test fun `a delivery needs a recorded winner and cannot exceed the prize`() {
        val noWinner = assertThrows(IllegalArgumentException::class.java) { FinancialService.record(db, "d0", "DELIVERY", "M1", "G1", 10_00, "Cash", null, "") }
        assertTrue(noWinner.message!!.contains("winner"))

        ChitAdminService.setWinner(db, "G1", 1, "M1", 100_00, "01-Jan-2026")
        FinancialService.record(db, "d1", "DELIVERY", "M1", "G1", 60_00, "Cash", null, "")
        val over = assertThrows(IllegalArgumentException::class.java) { FinancialService.record(db, "d2", "DELIVERY", "M1", "G1", 50_00, "Cash", null, "") }
        assertTrue(over.message!!.contains("₹40 left of ₹100"))
        FinancialService.record(db, "d3", "DELIVERY", "M1", "G1", 40_00, "Cash", null, "") // exactly the rest
        assertThrows(IllegalArgumentException::class.java) { FinancialService.record(db, "d4", "DELIVERY", "M1", "G1", 1_00, "Cash", null, "") }
        // someone who did not win is refused even though they are in the chit
        assertThrows(IllegalArgumentException::class.java) { FinancialService.record(db, "d5", "DELIVERY", "M2", "G1", 1_00, "Cash", null, "") }
    }

    @Test fun `reversing a delivery frees that amount again, and settlements stay unrestricted`() {
        ChitAdminService.setWinner(db, "G1", 1, "M1", 100_00, "01-Jan-2026")
        val id = FinancialService.record(db, "d1", "DELIVERY", "M1", "G1", 100_00, "Cash", null, "")
        assertThrows(IllegalArgumentException::class.java) { FinancialService.record(db, "d2", "DELIVERY", "M1", "G1", 1_00, "Cash", null, "") }
        FinancialService.reverse(db, id, "paid twice")
        FinancialService.record(db, "d3", "DELIVERY", "M1", "G1", 100_00, "Cash", null, "")
        // settlements are a different payment and do not need a winner
        FinancialService.record(db, "s1", "SETTLEMENT", "M2", "G1", 500_00, "Cash", null, "")
    }
}
