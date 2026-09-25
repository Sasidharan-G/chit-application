package com.jothivel.chits.data.local

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemberEnrollmentServiceTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = TestDb.newDb()
        TestDb.seedGroup(db, "G1", months = 3, installmentPaise = 100_00, subscribers = 3)
        TestDb.seedGroup(db, "G2", months = 4, installmentPaise = 50_00, subscribers = 4, registerNo = "REG-2")
    }

    @After fun tearDown() = db.close()

    private fun input(code: String = "C-1", name: String = "Ramesh", phone: String = "9876543210", extra: NewMemberInput.() -> NewMemberInput = { this }) =
        NewMemberInput(customerCode = code, name = name, phone = phone, joiningDate = "01-Jan-2026", dueDate = "01-Apr-2026").extra()

    @Test fun `adds a brand new customer with nominee, ticket and masked Aadhaar`() {
        val result = MemberEnrollmentService.addMemberToChit(
            db, "G1",
            input().copy(address = "1 Main St", city = "Madurai", nomineeName = "Lakshmi", nomineePhone = "9000000001", nomineeRelationship = "Wife", aadhaarLast4 = "4321", ticketNo = "2")
        )
        assertFalse(result.reusedExistingCustomer)
        val m = db.memberDao().getMemberByIdSync("C-1")!!
        assertEquals("Ramesh", m.name)
        assertEquals("Lakshmi", m.nomineeName)
        assertEquals("9000000001", m.nomineePhone)
        assertEquals("Wife", m.nomineeRelationship)
        assertEquals("XXXXXXXX4321", m.aadhaarNoEncrypted)
        assertEquals("Madurai", m.city)
        val ms = db.membershipDao().getSync("C-1", "G1")!!
        assertEquals("2", ms.ticketNo)
        assertTrue(ms.isActive)
        assertTrue(db.activityLogDao().getByTypeSync("MEMBER_ADDED").isNotEmpty())
    }

    @Test fun `ticket, Aadhaar and nominee details are optional`() {
        val result = MemberEnrollmentService.addMemberToChit(db, "G1", input())
        assertFalse(result.reusedExistingCustomer)
        val m = db.memberDao().getMemberByIdSync("C-1")!!
        assertNull(m.nomineeName)
        assertNull(m.nomineePhone)
        assertNull(m.nomineeRelationship)
        assertNull(m.aadhaarNoEncrypted)
        val ms = db.membershipDao().getSync("C-1", "G1")!!
        assertNull(ms.ticketNo)
        assertTrue(ms.isActive)
    }

    @Test fun `an existing customer code for a different person is refused, not silently reused`() {
        MemberEnrollmentService.addMemberToChit(db, "G1", input())
        val differentPerson = assertThrows(IllegalStateException::class.java) {
            MemberEnrollmentService.addMemberToChit(db, "G2", input(code = "c-1", name = "Suresh", phone = "9111111111"))
        }
        assertTrue(differentPerson.message!!.contains("already belongs to Ramesh"))
        assertNull(db.membershipDao().getSync("C-1", "G2"))
        assertEquals("Ramesh", db.memberDao().getMemberByIdSync("C-1")!!.name)

        // same code + same mobile but a different name is also refused
        assertThrows(IllegalStateException::class.java) { MemberEnrollmentService.addMemberToChit(db, "G2", input(name = "Somebody Else")) }
    }

    @Test fun `the same person joins a second chit under the same code or under a new one`() {
        MemberEnrollmentService.addMemberToChit(db, "G1", input())
        val sameCode = MemberEnrollmentService.addMemberToChit(db, "G2", input(name = "RAMESH"))
        assertTrue(sameCode.reusedExistingCustomer)
        assertEquals("C-1", sameCode.memberId)

        TestDb.seedGroup(db, "G3", months = 2, installmentPaise = 10_00, subscribers = 2, registerNo = "REG-3")
        val newCode = MemberEnrollmentService.addMemberToChit(db, "G3", input(code = "C-99"))
        assertTrue(newCode.reusedExistingCustomer)
        assertEquals("C-1", newCode.memberId) // resolved by mobile - no duplicate customer created
        assertEquals(1, db.memberDao().getAllMembersSync().size)
    }

    @Test fun `a mobile owned by someone else is refused`() {
        MemberEnrollmentService.addMemberToChit(db, "G1", input())
        val error = assertThrows(IllegalStateException::class.java) {
            MemberEnrollmentService.addMemberToChit(db, "G1", input(code = "C-2", name = "Suresh"))
        }
        assertTrue(error.message!!.contains("Ramesh"))
    }

    @Test fun `a duplicate join is refused, and a member who left is reactivated instead`() {
        MemberEnrollmentService.addMemberToChit(db, "G1", input())
        assertThrows(IllegalStateException::class.java) { MemberEnrollmentService.addMemberToChit(db, "G1", input()) }

        ChitAdminService.deactivateMember(db, "C-1", "G1")
        val result = MemberEnrollmentService.addMemberToChit(db, "G1", input())
        assertTrue(result.rejoined)
        assertTrue(db.membershipDao().getSync("C-1", "G1")!!.isActive)
        assertEquals(1, db.membershipDao().getAllForGroupSync("G1").size)
    }

    @Test fun `the chit size and the ticket number are enforced`() {
        val g = db.groupDao().getGroupByIdSync("G2")!!
        for (i in 1..g.subscriberCount) {
            MemberEnrollmentService.addMemberToChit(db, "G2", input(code = "C-$i", name = "N$i", phone = "98765432${10 + i}", extra = { copy(ticketNo = "$i") }))
        }
        val full = assertThrows(IllegalStateException::class.java) {
            MemberEnrollmentService.addMemberToChit(db, "G2", input(code = "C-9", name = "Late", phone = "9000000009"))
        }
        assertTrue(full.message!!.contains("full"))

        val taken = assertThrows(IllegalStateException::class.java) {
            MemberEnrollmentService.addMemberToChit(db, "G1", input(code = "C-5", name = "Fifth", phone = "9000000005", extra = { copy(ticketNo = "1") }))
            MemberEnrollmentService.addMemberToChit(db, "G1", input(code = "C-6", name = "Sixth", phone = "9000000006", extra = { copy(ticketNo = "1") }))
        }
        assertTrue(taken.message!!.contains("Ticket 1"))
        assertThrows(IllegalArgumentException::class.java) {
            MemberEnrollmentService.addMemberToChit(db, "G1", input(code = "C-7", name = "Seventh", phone = "9000000007", extra = { copy(ticketNo = "9") }))
        }
    }

    @Test fun `input is validated`() {
        fun bad(change: NewMemberInput.() -> NewMemberInput) =
            assertThrows(IllegalArgumentException::class.java) { MemberEnrollmentService.addMemberToChit(db, "G1", input(extra = change)) }
        bad { copy(customerCode = " ") }
        bad { copy(name = "") }
        bad { copy(phone = "12345") }
        bad { copy(phone = "98765abcde") }
        bad { copy(aadhaarLast4 = "12") }
        bad { copy(nomineePhone = "123") }
        bad { copy(joiningDate = "2026-01-01") }
        bad { copy(dueDate = "31-Dec-2025") }
        assertEquals(0, db.memberDao().getAllMembersSync().size)
    }

    @Test fun `closed chits refuse new members`() {
        db.groupDao().updateStatus("G1", "COMPLETED")
        assertThrows(IllegalStateException::class.java) { MemberEnrollmentService.addMemberToChit(db, "G1", input()) }
    }

    @Test fun `a flat amount equal to the chit default is stored as no override so the remainder is collected`() {
        // 100000 paise over 7 months = 14285 flat (rupee-floored 142*100...) - build a real remainder case.
        TestDb.seedGroup(db, "G7", months = 7, installmentPaise = 1, subscribers = 7, registerNo = "REG-7")
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("G7")!!.apply { chitValue = 100_000_00 })
        db.installmentDao().insertAll(ChitAdminService.buildInstallments("G7", 100_000_00, 7, null))
        val group = db.groupDao().getGroupByIdSync("G7")!!
        val default = MemberEnrollmentService.defaultFlatInstallmentPaise(group)
        assertEquals(14285_00L, default)

        MemberEnrollmentService.addMemberToChit(db, "G7", input(code = "D1", phone = "9000000101", extra = { copy(installmentPaise = default) }))
        assertEquals(0L, db.membershipDao().getSync("D1", "G7")!!.installmentAmountPaise)

        MemberEnrollmentService.addMemberToChit(db, "G7", input(code = "D2", name = "Custom", phone = "9000000102", extra = { copy(installmentPaise = 20000_00L) }))
        assertEquals(20000_00L, db.membershipDao().getSync("D2", "G7")!!.installmentAmountPaise)

        // D1 follows the chit's own schedule, so over the whole chit they owe exactly the chit value.
        val far = java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH).parse("01-Dec-2026")!!
        assertEquals(100_000_00L, CollectionService.calculateDueBreakdown(db, "D1", "G7", far).payablePaise)
    }

    @Test fun `fixed schedule chits never store a per-member override`() {
        val plan = com.jothivel.chits.data.models.ChitTemplate.PLAN_1L
        db.groupDao().insertGroup(com.jothivel.chits.data.local.entity.ChitGroupEntity().apply {
            id = "GF"; name = "Fixed"; registerNo = "REG-F"; chitValue = plan.chitValue * 100; durationMonths = 20; subscriberCount = 20
            branch = "Main"; startDate = "01-Jan-2026"; status = "ACTIVE"
        })
        db.installmentDao().insertAll(ChitAdminService.buildInstallments("GF", plan.chitValue * 100, 20, plan.fixedSchedule))
        MemberEnrollmentService.addMemberToChit(db, "GF", input(code = "F1", phone = "9000000201", extra = { copy(installmentPaise = 5000_00L) }))
        assertEquals(0L, db.membershipDao().getSync("F1", "GF")!!.installmentAmountPaise)
    }
}
