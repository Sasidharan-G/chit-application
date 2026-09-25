package com.jothivel.chits.ui.settings

import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CsvImportApplyTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = TestDb.newDb()
    }

    @After fun tearDown() = db.close()

    private fun sheetMember(name: String, phone: String) = MemberEntity().apply {
        id = UUID.randomUUID().toString(); this.name = name; this.phone = phone; isActive = true; role = "MEMBER"
    }

    private fun sheetGroup(name: String) = ChitGroupEntity().apply {
        id = UUID.randomUUID().toString(); this.name = name; registerNo = "IMP-placeholder"; chitValue = 100_000_00
        durationMonths = 20; subscriberCount = 20; branch = "Main"; startDate = "2026-01-01"; status = "ACTIVE"
    }

    private fun row(phone: String, group: String, no: Int, paid: Long, status: String = "PAID", mode: String = "CASH", due: String = "2026-03-01", paidDate: String = "") =
        ImportPaymentRow(phone, group, no, 5000_00, 5000_00, paid, status, mode, due, paidDate)

    @Test fun `an import creates the chit, member, installment, membership, payment and a matching receipt`() {
        val summary = applyImport(db, listOf(sheetMember("Ramesh", "9876543210")), listOf(sheetGroup("5L/20M")), listOf(row("9876543210", "5L/20M", 3, 5000_00)))
        assertEquals(1, summary.newMembers)
        assertEquals(1, summary.newGroups)
        assertEquals(1, summary.newInstallments)
        assertEquals(1, summary.newMemberships)
        assertEquals(1, summary.newPayments)

        val payment = db.paymentDao().getAllPaymentsSync().single()
        val receipt = db.collectionReceiptDao().getRecentSync(10).single()
        assertEquals(payment.receiptNo, receipt.receiptNo)
        assertEquals(5000_00L, receipt.amountPaidPaise)
        assertEquals("Cash", receipt.mode)
        assertEquals("SAVED", receipt.status)
        assertEquals("2026-03-01", receipt.businessDate)
        assertEquals(payment.paidAt, receipt.paidAt)
        assertTrue(receipt.requestId.startsWith("IMPORT-"))
    }

    @Test fun `the payment date column wins over the due date, which is only a fallback`() {
        applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("G")), listOf(row("9876543210", "G", 1, 5000_00, due = "2026-03-01", paidDate = "2026-02-27")))
        assertEquals("2026-02-27", db.collectionReceiptDao().getRecentSync(10).single().businessDate)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2026-02-27", fmt.format(java.util.Date(db.paymentDao().getAllPaymentsSync().single().paidAt)))
    }

    @Test fun `mode names map onto the app's receipt modes`() {
        val rows = listOf(
            row("9876543210", "G", 1, 5000_00, mode = "UPI"),
            row("9876543210", "G", 2, 5000_00, mode = "BANK_TRANSFER"),
            row("9876543210", "G", 3, 5000_00, mode = "CASH")
        )
        applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("G")), rows)
        assertEquals(setOf("UPI", "Bank", "Cash"), db.collectionReceiptDao().getRecentSync(10).map { it.mode }.toSet())
    }

    @Test fun `re-importing the same sheet does not duplicate payments or receipts`() {
        val args = { applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("G")), listOf(row("9876543210", "G", 1, 5000_00))) }
        args()
        val second = args()
        assertEquals(0, second.newPayments)
        assertEquals(1, db.paymentDao().getAllPaymentsSync().size)
        assertEquals(1, db.collectionReceiptDao().getRecentSync(10).size)
        assertEquals(1, db.groupDao().getAllGroupsSync().size)
        assertEquals(1, db.memberDao().getAllMembersSync().size)
    }

    @Test fun `an existing chit is matched but never overwritten by the sheet's placeholder values`() {
        TestDb.seedGroup(db, "REAL", registerNo = "214/2020", months = 20, installmentPaise = 5000_00, start = "05-Mar-2024", subscribers = 20)
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("REAL")!!.apply { name = "5L/20M"; branch = "Madurai" })

        val summary = applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("5L/20M")), listOf(row("9876543210", "5L/20M", 2, 2000_00, "PARTIAL")))
        assertEquals(0, summary.newGroups)
        assertEquals(1, summary.updatedGroups)
        assertEquals(1, db.groupDao().getAllGroupsSync().size)
        val g = db.groupDao().getGroupByIdSync("REAL")!!
        assertEquals("214/2020", g.registerNo)
        assertEquals("05-Mar-2024", g.startDate)
        assertEquals("Madurai", g.branch)
        assertEquals("REAL", db.paymentDao().getAllPaymentsSync().single().groupId)
    }

    @Test fun `a chit can be matched by its chit number`() {
        TestDb.seedGroup(db, "REAL", registerNo = "214/2020", months = 20, installmentPaise = 5000_00)
        applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("214/2020")), listOf(row("9876543210", "214/2020", 1, 5000_00)))
        assertEquals(1, db.groupDao().getAllGroupsSync().size)
        assertEquals("REAL", db.paymentDao().getAllPaymentsSync().single().groupId)
    }

    @Test fun `two chits with the same name stop the import and change nothing`() {
        TestDb.seedGroup(db, "A", registerNo = "R-A")
        TestDb.seedGroup(db, "B", registerNo = "R-B")
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("A")!!.apply { name = "Twin" })
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("B")!!.apply { name = "Twin" })

        val error = assertThrows(IllegalStateException::class.java) {
            db.runInTransaction(Runnable {
                applyImport(db, listOf(sheetMember("R", "9876543210")), listOf(sheetGroup("Twin")), listOf(row("9876543210", "Twin", 1, 5000_00)))
            })
        }
        assertTrue(error.message!!.contains("More than one chit is named 'Twin'"))
        assertEquals(0, db.paymentDao().getAllPaymentsSync().size)
        assertEquals(0, db.memberDao().getAllMembersSync().size)
    }

    @Test fun `a failure halfway rolls the whole import back`() {
        TestDb.seedGroup(db, "A", registerNo = "R-A")
        TestDb.seedGroup(db, "B", registerNo = "R-B")
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("A")!!.apply { name = "Twin" })
        db.groupDao().insertGroup(db.groupDao().getGroupByIdSync("B")!!.apply { name = "Twin" })
        // First group is fine and would be inserted; the second is ambiguous and aborts.
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction(Runnable {
                applyImport(
                    db, listOf(sheetMember("R", "9876543210")),
                    listOf(sheetGroup("Brand New"), sheetGroup("Twin")),
                    listOf(row("9876543210", "Brand New", 1, 5000_00))
                )
            })
        }
        assertNull(db.groupDao().getGroupByNameSync("Brand New"))
        assertEquals(2, db.groupDao().getAllGroupsSync().size)
    }

    @Test fun `existing members keep the details the sheet cannot supply`() {
        TestDb.seedMember(db, "C-7", "Old Name", "9876543210")
        db.memberDao().insertMember(db.memberDao().getMemberByIdSync("C-7")!!.apply { city = "Madurai"; nomineeName = "Lakshmi"; aadhaarNoEncrypted = "XXXXXXXX1234" })
        val summary = applyImport(db, listOf(sheetMember("Ramesh", "9876543210")), listOf(sheetGroup("G")), listOf(row("9876543210", "G", 1, 5000_00)))
        assertEquals(1, summary.updatedMembers)
        val m = db.memberDao().getMemberByPhoneSync("9876543210")!!
        assertEquals("C-7", m.id)
        assertEquals("Ramesh", m.name)
        assertEquals("Madurai", m.city)
        assertEquals("Lakshmi", m.nomineeName)
        assertNotNull(m.aadhaarNoEncrypted)
    }
}
