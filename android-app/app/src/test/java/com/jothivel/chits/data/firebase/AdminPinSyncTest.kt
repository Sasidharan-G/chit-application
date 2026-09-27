package com.jothivel.chits.data.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.data.firebase.AdminPinSync.Action
import com.jothivel.chits.data.firebase.AdminPinSync.LocalPin
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminPinSyncTest {
    private lateinit var app: Application
    private lateinit var prefs: AppPreferences

    /** A cloud that can be online, offline, empty or holding a PIN. */
    private class FakeStore(var saved: CloudPin? = null, var online: Boolean = true) : PinStore {
        var writes = 0
        override suspend fun read(context: Context): CloudPin? {
            if (!online) throw java.io.IOException("offline")
            return saved
        }
        override suspend fun write(context: Context, pin: CloudPin) {
            if (!online) throw java.io.IOException("offline")
            saved = pin; writes++
        }
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = AppPreferences(app)
        app.getSharedPreferences("jothivel_chits_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CloudAccount.setEnabled(app, true)
    }

    @After fun tearDown() = CloudAccount.setEnabled(app, true)

    private fun hash(pin: String) = AppPreferences.hashPin(pin)

    // ── the decision ──────────────────────────────────────────────────────────────────────
    @Test fun `the decision table`() {
        val a = LocalPin("h1", changedAt = 100, dirty = false)
        assertEquals(Action.NOTHING, AdminPinSync.decide(null, null))
        assertEquals(Action.ADOPT, AdminPinSync.decide(null, CloudPin("h2", 50)))
        assertEquals(Action.PUSH, AdminPinSync.decide(a, null))
        assertEquals(Action.NOTHING, AdminPinSync.decide(a, CloudPin("h1", 100)))
        assertEquals(Action.MARK_SYNCED, AdminPinSync.decide(a.copy(dirty = true), CloudPin("h1", 100)))
        assertEquals(Action.PUSH, AdminPinSync.decide(a.copy(dirty = true), CloudPin("h2", 50)))
        assertEquals(Action.ADOPT, AdminPinSync.decide(a.copy(dirty = true), CloudPin("h2", 500)))
        assertEquals(Action.ADOPT, AdminPinSync.decide(a, CloudPin("h2", 500)))
        assertEquals(Action.NOTHING, AdminPinSync.decide(a, CloudPin("h2", 50)))
        assertEquals(Action.ADOPT, AdminPinSync.decide(a.copy(changedAt = 0), CloudPin("h2", 50)))
    }

    // ── changing the PIN ────────────────────────────────────────────────────────────────
    @Test fun `a PIN change works at once on the phone and is remembered as not yet in the cloud`() = runBlocking {
        prefs.savePin("4826")
        assertTrue(prefs.changePin("4826", "5937"))
        assertTrue(prefs.verifyPin("5937"))
        assertFalse(prefs.verifyPin("4826"))
        assertTrue(prefs.isPinDirty())
        assertTrue(prefs.getPinChangedAt() > 0)
    }

    @Test fun `online, the change reaches the cloud straight away`() = runBlocking {
        val cloud = FakeStore(CloudPin(hash("4826"), 10))
        prefs.savePin("4826"); prefs.markPinSynced(10)
        prefs.changePin("4826", "5937")
        assertEquals(AdminPinSync.Result.Pushed, AdminPinSync.sync(app, cloud))
        assertTrue(AppPreferences.verifyPinHash("5937", cloud.saved!!.pinHash))
        assertFalse(prefs.isPinDirty())
    }

    @Test fun `offline, the change is kept and goes up when the phone is online again`() = runBlocking {
        val cloud = FakeStore(CloudPin(hash("4826"), 10), online = false)
        prefs.savePin("4826"); prefs.markPinSynced(10)
        prefs.changePin("4826", "5937")

        assertEquals(AdminPinSync.Result.Unreachable, AdminPinSync.sync(app, cloud))
        assertTrue(prefs.isPinDirty())
        assertTrue("the phone itself already uses the new PIN", prefs.verifyPin("5937"))
        assertTrue("the cloud still has the old one", AppPreferences.verifyPinHash("4826", cloud.saved!!.pinHash))

        cloud.online = true
        assertEquals(AdminPinSync.Result.Pushed, AdminPinSync.sync(app, cloud))
        assertTrue(AppPreferences.verifyPinHash("5937", cloud.saved!!.pinHash))
        assertFalse(prefs.isPinDirty())
    }

    // ── several phones ──────────────────────────────────────────────────────────────────
    @Test fun `the first phone uploads its PIN when the cloud has none`() = runBlocking {
        val cloud = FakeStore()
        prefs.savePin("4826")
        assertEquals(AdminPinSync.Result.Pushed, AdminPinSync.sync(app, cloud))
        assertTrue(AppPreferences.verifyPinHash("4826", cloud.saved!!.pinHash))
        assertTrue(cloud.saved!!.changedAt > 0)
    }

    @Test fun `a change made on another phone is picked up`() = runBlocking {
        prefs.savePin("4826"); prefs.markPinSynced(100)
        val cloud = FakeStore(CloudPin(hash("5937"), 200))
        assertEquals(AdminPinSync.Result.Adopted, AdminPinSync.sync(app, cloud))
        assertTrue(prefs.verifyPin("5937"))
        assertFalse(prefs.verifyPin("4826"))
        assertEquals(0, cloud.writes)
    }

    @Test fun `a newer change on this phone beats an older one in the cloud`() = runBlocking {
        prefs.savePin("4826"); prefs.markPinSynced(100)
        val cloud = FakeStore(CloudPin(hash("4826"), 100))
        prefs.changePin("4826", "5937")
        assertEquals(AdminPinSync.Result.Pushed, AdminPinSync.sync(app, cloud))
        assertTrue(prefs.verifyPin("5937"))
    }

    @Test fun `a second phone that chose its own first PIN takes the account's PIN when it connects`() = runBlocking {
        prefs.savePin("7391") // set locally on the fresh phone, never shared
        val cloud = FakeStore(CloudPin(hash("4826"), 100))
        assertEquals(AdminPinSync.Result.Adopted, AdminPinSync.sync(app, cloud))
        assertTrue(prefs.verifyPin("4826"))
    }

    @Test fun `without a cloud account nothing is synced`() = runBlocking {
        CloudAccount.setEnabled(app, false)
        val cloud = FakeStore()
        prefs.savePin("4826")
        assertEquals(AdminPinSync.Result.NotConfigured, AdminPinSync.sync(app, cloud))
        assertNull(cloud.saved)
    }

    @Test fun `a PIN still in the old plain form is never sent to the cloud`() = runBlocking {
        app.getSharedPreferences("jothivel_chits_prefs", Context.MODE_PRIVATE).edit().putString("login_pin", "4826").commit()
        val cloud = FakeStore()
        assertEquals(AdminPinSync.Result.UpToDate, AdminPinSync.sync(app, cloud))
        assertNull(cloud.saved)
    }

    // ── a new phone joining ─────────────────────────────────────────────────────────────
    @Test fun `a new phone takes over the existing PIN when it is typed right`() = runBlocking {
        val cloud = FakeStore(CloudPin(hash("4826"), 100))
        assertEquals(AdminPinSync.Restore.Restored, AdminPinSync.restoreOnNewPhone(app, "4826", cloud))
        assertTrue(prefs.verifyPin("4826"))
        assertFalse(prefs.isUsingDefaultPin())
        assertFalse(prefs.isPinDirty())
    }

    @Test fun `a wrong existing PIN, an empty cloud and no internet are told apart`() = runBlocking {
        val cloud = FakeStore(CloudPin(hash("4826"), 100))
        assertEquals(AdminPinSync.Restore.WrongPin, AdminPinSync.restoreOnNewPhone(app, "0000", cloud))
        assertTrue("nothing was adopted", prefs.isUsingDefaultPin())

        cloud.saved = null
        assertEquals(AdminPinSync.Restore.NoCloudPin, AdminPinSync.restoreOnNewPhone(app, "4826", cloud))

        cloud.online = false
        assertEquals(AdminPinSync.Restore.Unreachable, AdminPinSync.restoreOnNewPhone(app, "4826", cloud))
        assertTrue(prefs.isUsingDefaultPin())
    }

    @Test fun `the first PIN chosen on a fresh phone is not marked as a shared change`() {
        assertTrue(prefs.verifyPin("1234")) // the factory default
        assertTrue(prefs.changePin("1234", "7391", share = false))
        assertFalse(prefs.isPinDirty())
        assertEquals(0L, prefs.getPinChangedAt())
    }
}
