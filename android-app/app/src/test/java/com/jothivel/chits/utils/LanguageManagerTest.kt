package com.jothivel.chits.utils

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class LanguageManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        context.getSharedPreferences("jothivel_chits_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ChitsLanguagePrefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `the login screen and the settings toggle share one stored language`() {
        LanguageManager.setLanguage(context, "ta")
        assertEquals("ta", AppPreferences(context).getLanguage())     // what LocaleHelper / Settings read
        AppPreferences(context).setLanguage("en")                       // Settings toggle
        assertEquals("en", LanguageManager.getLanguage(context))        // login screen sees it
    }

    @Test fun `a language saved by the old separate store is migrated once`() {
        context.getSharedPreferences("ChitsLanguagePrefs", Context.MODE_PRIVATE).edit().putString("app_language", "ta").commit()
        assertFalse(AppPreferences(context).isLanguageSet())
        assertEquals("ta", LanguageManager.getLanguage(context))
        assertTrue(AppPreferences(context).isLanguageSet())
        assertEquals("ta", AppPreferences(context).getLanguage())
        assertFalse(context.getSharedPreferences("ChitsLanguagePrefs", Context.MODE_PRIVATE).contains("app_language"))
    }

    @Test fun `an already chosen language wins over a stale old value`() {
        AppPreferences(context).setLanguage("en")
        context.getSharedPreferences("ChitsLanguagePrefs", Context.MODE_PRIVATE).edit().putString("app_language", "ta").commit()
        assertEquals("en", LanguageManager.getLanguage(context))
        assertFalse(context.getSharedPreferences("ChitsLanguagePrefs", Context.MODE_PRIVATE).contains("app_language"))
    }

    @Test fun `the default is English`() {
        assertEquals("en", LanguageManager.getLanguage(context))
    }
}
