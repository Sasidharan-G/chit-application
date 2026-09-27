package com.jothivel.chits.data.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.data.firebase.SessionGuard.Claim
import com.jothivel.chits.data.firebase.SessionGuard.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionGuardTest {
    private val now = 1_000_000_000L
    private val minute = 60_000L

    private fun other(idleMinutes: Long, name: String = "Redmi Note 9") =
        SessionRecord(deviceId = "phone-A", deviceName = name, lastSeenMs = now - idleMinutes * minute)

    @Test fun `the first login on an account takes it`() {
        assertEquals(Claim.Held, SessionGuard.decide(null, "phone-B", now))
    }

    @Test fun `a second phone is refused while the first is live`() {
        val result = SessionGuard.decide(other(idleMinutes = 1), "phone-B", now)
        assertEquals(Claim.Blocked("Redmi Note 9"), result)
        assertEquals("Already logged in on another phone (Redmi Note 9). Log out there first.", (result as Claim.Blocked).message)
    }

    @Test fun `the same phone can log in again and keeps its account`() {
        assertEquals(Claim.Held, SessionGuard.decide(other(idleMinutes = 1), "phone-A", now))
    }

    @Test fun `a phone that stopped checking in frees the account after the live window`() {
        val window = SessionGuard.LIVE_WINDOW_MS / minute
        assertTrue(SessionGuard.decide(other(window - 1), "phone-B", now) is Claim.Blocked)
        assertEquals(Claim.Held, SessionGuard.decide(other(window), "phone-B", now))
        assertEquals(Claim.Held, SessionGuard.decide(other(window + 600), "phone-B", now))
    }

    @Test fun `a record from a phone whose clock runs ahead still counts as live`() {
        val ahead = SessionRecord("phone-A", "Redmi Note 9", now + 5 * minute)
        assertTrue(SessionGuard.decide(ahead, "phone-B", now) is Claim.Blocked)
    }

    @Test fun `a nameless record is still described`() {
        assertEquals("Already logged in on another phone (another phone). Log out there first.", (SessionGuard.decide(other(1, name = ""), "phone-B", now) as Claim.Blocked).message)
    }

    @Test fun `a phone keeps the same id between launches`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = SessionGuard.deviceId(app)
        assertEquals(first, SessionGuard.deviceId(app))
        assertTrue(first.length >= 16)
    }

    @Test fun `a reinstall on the same physical phone keeps the same id (ANDROID_ID)`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID, "hardware-id-abc123")
        app.getSharedPreferences("jvc_device", 0).edit().clear().commit() // uninstall wipes this app's own storage
        val afterInstall1 = SessionGuard.deviceId(app)
        assertEquals("hardware-id-abc123", afterInstall1)

        app.getSharedPreferences("jvc_device", 0).edit().clear().commit() // simulate an uninstall + reinstall
        val afterInstall2 = SessionGuard.deviceId(app)
        assertEquals("this is the whole point of the fix - the same phone must not look like a new one", afterInstall1, afterInstall2)
    }

    @Test fun `a blank or known-broken ANDROID_ID falls back to a random id, stable only per install`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (broken in listOf("", "9774d56d682e549c")) {
            android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID, broken)
            app.getSharedPreferences("jvc_device", 0).edit().clear().commit()
            val id = SessionGuard.deviceId(app)
            assertNotEquals(broken, id)
            assertTrue(id.length >= 16)
        }
    }

    @Test fun `cloud sync off means no blocking at admin login`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CloudAccount.setEnabled(app, false)
        assertEquals(null, SessionGuard.adminLoginBlockMessage(app))
        CloudAccount.setEnabled(app, true)
    }

    @Test fun `the admin email is fixed, and cloud sync can be turned off for this phone`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CloudAccount.setEnabled(app, true)
        assertEquals(AdminAccount.EMAIL, CloudAccount.email(app))
        assertTrue(AdminAccount.hasCredentials)
        assertTrue(CloudAccount.isConfigured(app))

        CloudAccount.setEnabled(app, false)
        assertEquals(AdminAccount.EMAIL, CloudAccount.email(app)) // still fixed even when off
        assertEquals(false, CloudAccount.isConfigured(app))

        CloudAccount.setEnabled(app, true)
        assertEquals(true, CloudAccount.isConfigured(app))
    }
}
