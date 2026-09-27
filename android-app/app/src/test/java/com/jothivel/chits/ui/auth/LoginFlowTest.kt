package com.jothivel.chits.ui.auth

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.data.firebase.AgentAuthRepository
import com.jothivel.chits.data.firebase.AgentLoginResult
import com.jothivel.chits.data.firebase.CloudAccount
import com.jothivel.chits.utils.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginFlowTest {
    private lateinit var app: Application
    private lateinit var prefs: AppPreferences

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = AppPreferences(app)
        prefs.savePin("4826")
        app.getSharedPreferences("jvc_agent_login_attempts", Context.MODE_PRIVATE).edit().clear().apply()
        // These tests exercise the PIN check itself, not the cloud one-device-login guard (see
        // SessionGuardTest) - without this, a correct PIN would try to reach the real Firebase project.
        CloudAccount.setEnabled(app, false)
    }

    /** The PIN check runs on a worker thread; let it finish and deliver its result to the main thread. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `the same wrong PIN twice is reported twice so the screen resets both times`() {
        val vm = LoginViewModel(app)
        vm.verifyPin("0000")
        settle { vm.errorSeq.value == 1 }
        assertEquals(1, vm.errorSeq.value)
        assertEquals("Wrong PIN. Try again.", vm.loginError.value)
        assertFalse(vm.isLoading.value!!)

        vm.verifyPin("1111")
        settle { vm.errorSeq.value == 2 }
        // Identical message, but the counter moved: this is what lets the dots clear the second time.
        assertEquals(2, vm.errorSeq.value)
        assertEquals("Wrong PIN. Try again.", vm.loginError.value)
        assertFalse(vm.isLoading.value!!)
    }

    @Test fun `the right PIN logs in`() {
        val vm = LoginViewModel(app)
        vm.verifyPin("4826")
        settle { vm.loginSuccess.value == true }
        assertEquals(true, vm.loginSuccess.value)
        assertEquals(0, vm.errorSeq.value)
    }

    @Test fun `after five wrong PINs the admin is told to wait`() {
        val vm = LoginViewModel(app)
        for (i in 1..5) {
            vm.verifyPin("000$i")
            settle { vm.errorSeq.value == i }
        }
        vm.verifyPin("4826") // even the right PIN is refused while throttled
        settle { vm.errorSeq.value == 6 }
        assertNotNull(vm.loginError.value)
        assertTrue(vm.loginError.value!!.startsWith("Too many attempts"))
        assertTrue(vm.loginSuccess.value != true)
    }

    private fun cachedAgent(pin: String, active: Boolean = true) =
        prefs.saveAgentSession("uid-1", "Kumar", "9876543210", AppPreferences.hashPin(pin), active, listOf("G1"))

    @Test fun `an agent who logged in online before can open the app offline with the same PIN`() {
        cachedAgent("2580")
        val result = AgentAuthRepository.loginFromCache(app, prefs, "9876543210", "2580")
        assertEquals(AgentLoginResult.Success("uid-1", "Kumar", listOf("G1")), result)
    }

    @Test fun `offline with a wrong PIN, a new phone or a disconnected agent is refused with a short reason`() {
        cachedAgent("2580")
        assertEquals(AgentLoginResult.Failure("Wrong mobile number or PIN."), AgentAuthRepository.loginFromCache(app, prefs, "9876543210", "1357"))
        assertEquals(
            AgentLoginResult.Failure("No internet. Log in once with internet on this phone first."),
            AgentAuthRepository.loginFromCache(app, prefs, "9000000000", "2580")
        )
        cachedAgent("2580", active = false)
        assertEquals(AgentLoginResult.Failure("Your account has been disconnected. Contact admin."), AgentAuthRepository.loginFromCache(app, prefs, "9876543210", "2580"))
    }

    @Test fun `offline wrong PINs are throttled like online ones`() {
        cachedAgent("2580")
        repeat(5) { AgentAuthRepository.loginFromCache(app, prefs, "9876543210", "1357") }
        val attempts = app.getSharedPreferences("jvc_agent_login_attempts", Context.MODE_PRIVATE).getString("attempts", "")!!
        assertTrue(attempts.contains("\"count\":5"))
    }
}
