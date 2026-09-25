package com.jothivel.chits.data.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminPinTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    /** Records every cloud password change and answers with [result]. */
    private class FakeCloud(var result: Result<Unit> = Result.success(Unit)) : CloudPasswordChanger {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun change(context: Context, email: String, oldPassword: String, newPassword: String): Result<Unit> {
            calls += Triple(email, oldPassword, newPassword)
            return result
        }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        prefs = AppPreferences(context)
        prefs.savePin("4826")
        CloudAccount.clear(context)
    }

    @After fun tearDown() = CloudAccount.clear(context)

    @Test fun `without a cloud account only the login PIN changes`() = runBlocking {
        val cloud = FakeCloud()
        assertEquals(AdminPin.ChangeResult.Changed, AdminPin.changePin(context, "4826", "5937", cloud))
        assertTrue(prefs.verifyPin("5937"))
        assertFalse(prefs.verifyPin("4826"))
        assertTrue(cloud.calls.isEmpty())
    }

    @Test fun `with a cloud account the cloud password follows the new PIN`() = runBlocking {
        CloudAccount.save(context, "owner@example.com", AdminPin.cloudPassword("4826"))
        val cloud = FakeCloud()
        assertEquals(AdminPin.ChangeResult.Changed, AdminPin.changePin(context, "4826", "5937", cloud))
        assertEquals(listOf(Triple("owner@example.com", "JVC#4826", "JVC#5937")), cloud.calls)
        assertEquals("JVC#5937", CloudAccount.password(context))
        assertTrue(prefs.verifyPin("5937"))
    }

    @Test fun `when the cloud cannot be changed the PIN stays as it was`() = runBlocking {
        CloudAccount.save(context, "owner@example.com", AdminPin.cloudPassword("4826"))
        val cloud = FakeCloud(Result.failure(IllegalStateException("No internet connection.")))
        val result = AdminPin.changePin(context, "4826", "5937", cloud)
        assertEquals(AdminPin.ChangeResult.CloudFailed("No internet connection."), result)
        assertTrue("old PIN must still work", prefs.verifyPin("4826"))
        assertFalse(prefs.verifyPin("5937"))
        assertEquals("JVC#4826", CloudAccount.password(context))
    }

    @Test fun `a wrong current PIN or a bad new PIN changes nothing`() = runBlocking {
        CloudAccount.save(context, "owner@example.com", AdminPin.cloudPassword("4826"))
        val cloud = FakeCloud()
        assertEquals(AdminPin.ChangeResult.WrongCurrentPin, AdminPin.changePin(context, "0000", "5937", cloud))
        assertEquals(AdminPin.ChangeResult.InvalidNewPin, AdminPin.changePin(context, "4826", "59a7", cloud))
        assertEquals(AdminPin.ChangeResult.InvalidNewPin, AdminPin.changePin(context, "4826", "593", cloud))
        assertTrue(cloud.calls.isEmpty())
        assertTrue(prefs.verifyPin("4826"))
    }

    @Test fun `logging in moves an older separate cloud password onto the PIN once`() = runBlocking {
        CloudAccount.save(context, "owner@example.com", "console-password")
        val cloud = FakeCloud()
        assertTrue(AdminPin.alignCloudPassword(context, "4826", cloud))
        assertEquals(listOf(Triple("owner@example.com", "console-password", "JVC#4826")), cloud.calls)
        assertEquals("JVC#4826", CloudAccount.password(context))

        // Already on the PIN: nothing more to do.
        assertTrue(AdminPin.alignCloudPassword(context, "4826", cloud))
        assertEquals(1, cloud.calls.size)
    }

    @Test fun `a failed move keeps the old password so it can be retried`() = runBlocking {
        CloudAccount.save(context, "owner@example.com", "console-password")
        val cloud = FakeCloud(Result.failure(IllegalStateException("No internet connection.")))
        assertFalse(AdminPin.alignCloudPassword(context, "4826", cloud))
        assertEquals("console-password", CloudAccount.password(context))
    }

    @Test fun `no cloud account means nothing to align`() = runBlocking {
        val cloud = FakeCloud()
        assertTrue(AdminPin.alignCloudPassword(context, "4826", cloud))
        assertTrue(cloud.calls.isEmpty())
    }

    @Test fun `the cloud password is long enough for Firebase`() {
        assertTrue(AdminPin.cloudPassword("0000").length >= 6)
    }
}
