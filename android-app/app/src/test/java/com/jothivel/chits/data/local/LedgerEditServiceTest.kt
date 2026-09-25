package com.jothivel.chits.data.local

import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
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
class LedgerEditServiceTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = TestDb.newDb()
        TestDb.seedGroup(db, "G1", months = 3, subscribers = 3)
        TestDb.seedMember(db, "M1", "Ramesh", "9876543210")
        TestDb.seedMember(db, "M2", "Suresh", "9876543211")
        TestDb.seedMembership(db, "M1", "G1", ticket = "1")
        TestDb.seedMembership(db, "M2", "G1", ticket = "2")
    }

    @After fun tearDown() = db.close()

    private fun edit(member: String, column: Int, value: String) = LedgerEditService.edit(db, member, "G1", column, value)

    @Test fun `valid edits are saved and logged`() {
        edit("M1", LedgerEditService.COL_NAME, "  Ramesh Kumar ")
        edit("M1", LedgerEditService.COL_PHONE, "9000000001")
        edit("M1", LedgerEditService.COL_ADDRESS, "12 Temple Rd")
        edit("M1", LedgerEditService.COL_CITY, "Madurai")
        edit("M1", LedgerEditService.COL_OLD_CODE, "OLD-77")
        edit("M1", LedgerEditService.COL_TICKET, "3")
        val m = db.memberDao().getMemberByIdSync("M1")!!
        assertEquals("Ramesh Kumar", m.name)
        assertEquals("9000000001", m.phone)
        assertEquals("12 Temple Rd", m.addressLine)
        assertEquals("Madurai", m.city)
        assertEquals("OLD-77", m.panNo)
        assertEquals("3", db.membershipDao().getSync("M1", "G1")!!.ticketNo)
        assertEquals(6, db.activityLogDao().getByTypeSync("LEDGER_EDIT").size)
        assertTrue(db.activityLogDao().getByTypeSync("LEDGER_EDIT").any { it.description.contains("Ramesh → Ramesh Kumar") })
    }

    @Test fun `a duplicate ticket is refused instead of crashing, and nothing changes`() {
        val error = assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_TICKET, "2") }
        assertTrue(error.message!!.contains("already taken"))
        assertEquals("1", db.membershipDao().getSync("M1", "G1")!!.ticketNo)
        assertEquals(0, db.activityLogDao().getByTypeSync("LEDGER_EDIT").size)
    }

    @Test fun `tickets must fit the chit and blank clears the ticket rather than storing an empty string`() {
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_TICKET, "0") }
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_TICKET, "4") }
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_TICKET, "abc") }
        edit("M1", LedgerEditService.COL_TICKET, "")
        edit("M2", LedgerEditService.COL_TICKET, "") // two blank tickets must not collide on the unique index
        assertNull(db.membershipDao().getSync("M1", "G1")!!.ticketNo)
        assertNull(db.membershipDao().getSync("M2", "G1")!!.ticketNo)
    }

    @Test fun `phone numbers are validated and cannot duplicate another customer`() {
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_PHONE, "12345") }
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_PHONE, "98765abcde") }
        val clash = assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_PHONE, "9876543211") }
        assertTrue(clash.message!!.contains("Suresh"))
        edit("M1", LedgerEditService.COL_PHONE, "9876543210") // re-saving their own number is fine
        assertEquals("9876543210", db.memberDao().getMemberByIdSync("M1")!!.phone)
    }

    @Test fun `a name cannot be blanked`() {
        assertThrows(IllegalArgumentException::class.java) { edit("M1", LedgerEditService.COL_NAME, "   ") }
        assertEquals("Ramesh", db.memberDao().getMemberByIdSync("M1")!!.name)
    }

    @Test fun `unknown members, memberships and columns are refused`() {
        assertThrows(IllegalArgumentException::class.java) { edit("NOBODY", LedgerEditService.COL_NAME, "x") }
        assertThrows(IllegalArgumentException::class.java) { edit("M1", 3, "x") }
        TestDb.seedMember(db, "M9", "Loner", "9000000009")
        assertThrows(IllegalArgumentException::class.java) { edit("M9", LedgerEditService.COL_TICKET, "1") }
    }

    @Test fun `undo writes the previous value back`() {
        edit("M1", LedgerEditService.COL_CITY, "Chennai")
        edit("M1", LedgerEditService.COL_CITY, "")
        assertEquals("", db.memberDao().getMemberByIdSync("M1")!!.city)
    }
}
