package com.jothivel.chits.data.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.testutil.TestDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudDeltaSyncTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CloudPushLedger.clear(app)
        db = TestDb.newDb()
        TestDb.seedGroup(db, "G1", months = 3, installmentPaise = 100_00, subscribers = 10)
    }

    @After fun tearDown() {
        db.close()
        CloudPushLedger.clear(app)
    }

    private fun addCustomer(id: String) {
        TestDb.seedMember(db, id)
        TestDb.seedMembership(db, id, "G1")
    }

    /** Pretends every current document was sent to the cloud. */
    private fun markAllPushed(): MutableMap<String, String> =
        FirestoreDataSync.buildDocs(db, emptyMap()).associate { it.key to it.hash }.toMutableMap()

    private fun waiting(pushed: Map<String, String>): Set<String> =
        FirestoreDataSync.dirtySubjects(FirestoreDataSync.buildDocs(db, emptyMap())) { pushed[it] }

    // ── what counts as a "change" ─────────────────────────────────────────────────────────
    @Test fun `on a fresh phone everything is waiting, one entry per chit and customer`() {
        (1..5).forEach { addCustomer("M$it") }
        val subjects = waiting(emptyMap())
        assertEquals(setOf("G:G1", "M:M1", "M:M2", "M:M3", "M:M4", "M:M5"), subjects)
    }

    @Test fun `once everything is sent nothing is waiting`() {
        (1..3).forEach { addCustomer("M$it") }
        assertTrue(waiting(markAllPushed()).isEmpty())
    }

    @Test fun `adding a customer is one change even though it writes a customer and a membership`() {
        addCustomer("M1")
        val pushed = markAllPushed()
        addCustomer("M2")
        assertEquals(setOf("M:M2"), waiting(pushed))
    }

    @Test fun `editing a customer is one change`() {
        addCustomer("M1"); addCustomer("M2")
        val pushed = markAllPushed()
        db.memberDao().insertMember(db.memberDao().getMemberByIdSync("M2")!!.apply { name = "Renamed" })
        assertEquals(setOf("M:M2"), waiting(pushed))
    }

    @Test fun `a change to a customer's membership is a change for that customer`() {
        addCustomer("M1"); addCustomer("M2")
        val pushed = markAllPushed()
        db.membershipDao().updateTicketNo("M1", "G1", "7")
        assertEquals(setOf("M:M1"), waiting(pushed))
    }

    @Test fun `a new chit with its whole schedule is one change`() {
        val pushed = markAllPushed()
        TestDb.seedGroup(db, "G2", months = 6, installmentPaise = 50_00, subscribers = 6, registerNo = "REG-2")
        assertEquals(setOf("G:G2"), waiting(pushed))
    }

    @Test fun `changing who may see a chit is not a change - the access list is kept up to date separately`() {
        addCustomer("M1")
        val pushed = markAllPushed()
        val withAgents = FirestoreDataSync.buildDocs(db, mapOf("G1" to listOf("agent-1", "agent-2")))
        assertTrue(FirestoreDataSync.dirtySubjects(withAgents) { pushed[it] }.isEmpty())
    }

    // ── the "5 changes" rule ────────────────────────────────────────────────────────────
    @Test fun `four changes wait, the fifth sends them all`() {
        addCustomer("M1")
        val pushed = markAllPushed()
        (2..5).forEach { addCustomer("M$it") } // four new customers
        assertEquals(4, waiting(pushed).size)
        assertFalse(AutoCloudSync.shouldPush(waiting(pushed).size))

        addCustomer("M6")                      // the fifth
        assertEquals(5, waiting(pushed).size)
        assertTrue(AutoCloudSync.shouldPush(waiting(pushed).size))
    }

    @Test fun `the threshold is five`() {
        assertEquals(5, AutoCloudSync.THRESHOLD)
        assertFalse(AutoCloudSync.shouldPush(0))
        assertFalse(AutoCloudSync.shouldPush(4))
        assertTrue(AutoCloudSync.shouldPush(5))
        assertTrue(AutoCloudSync.shouldPush(50))
    }

    // ── the record of what was already sent ─────────────────────────────────────────────
    @Test fun `the content hash ignores the access list but sees every other change`() {
        val base = mapOf("name" to "Ramesh", "phone" to "9876543210", FirestoreSchema.AGENT_IDS to listOf("a"))
        assertEquals(CloudPushLedger.hash(base), CloudPushLedger.hash(base + (FirestoreSchema.AGENT_IDS to listOf("b", "c"))))
        assertNotEquals(CloudPushLedger.hash(base), CloudPushLedger.hash(base + ("name" to "Ramesh K")))
        assertNotEquals(CloudPushLedger.hash(base), CloudPushLedger.hash(base + ("city" to "Madurai")))
        // key order does not matter
        assertEquals(CloudPushLedger.hash(mapOf("a" to 1, "b" to 2)), CloudPushLedger.hash(mapOf("b" to 2, "a" to 1)))
    }

    @Test fun `the record of what was pushed survives turning cloud sync off and on`() {
        CloudPushLedger.putAll(app, mapOf("members/M1" to "abc"))
        assertEquals("abc", CloudPushLedger.get(app, "members/M1"))
        assertEquals(mapOf("members/M1" to "abc"), CloudPushLedger.snapshot(app))

        // There is only one cloud account, so disabling/enabling sync never touches it - it is the
        // same database either way.
        CloudAccount.setEnabled(app, false)
        assertEquals("abc", CloudPushLedger.get(app, "members/M1"))
        CloudAccount.setEnabled(app, true)
        assertEquals("abc", CloudPushLedger.get(app, "members/M1"))

        CloudPushLedger.clear(app)
        assertTrue(CloudPushLedger.snapshot(app).isEmpty())
    }

    @Test fun `without a cloud account the automatic sync does nothing`() = kotlinx.coroutines.runBlocking {
        CloudAccount.setEnabled(app, false)
        assertEquals(AutoCloudSync.Outcome.NotConfigured, AutoCloudSync.check(app))
        CloudAccount.setEnabled(app, true)
    }

    // ── agent accounts: which generations are tried at login ────────────────────────────
    @Test fun `login tries the last working account first, then every other one`() {
        assertEquals(listOf(5, 4, 3, 2, 1, 0), AgentAuthRepository.generationOrder(null))
        assertEquals(listOf(2, 5, 4, 3, 1, 0), AgentAuthRepository.generationOrder(2))
        assertEquals(listOf(5, 4, 3, 2, 1, 0), AgentAuthRepository.generationOrder(9)) // unknown value: ignored
    }
}
