package com.jothivel.chits.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Language selection for the login screen. This used to keep the language in its own
 * SharedPreferences file while [LocaleHelper] / the Settings toggle used [AppPreferences], so
 * choosing Tamil on the login screen and then opening the app showed English (or vice versa).
 * There is now exactly one stored language - [AppPreferences]'s - and this object is a thin
 * wrapper around it that also migrates a value saved by the old implementation.
 */
object LanguageManager {
    private const val LEGACY_PREFS_NAME = "ChitsLanguagePrefs"
    private const val LEGACY_KEY_LANGUAGE = "app_language"

    fun setLanguage(context: Context, languageCode: String) {
        AppPreferences(context).setLanguage(languageCode)
        updateResources(context, languageCode)
    }

    fun getLanguage(context: Context): String {
        val prefs = AppPreferences(context)
        migrateLegacyValue(context, prefs)
        return prefs.getLanguage()
    }

    /** Applies the language a previous version stored separately, once, then forgets it. */
    private fun migrateLegacyValue(context: Context, prefs: AppPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacy.getString(LEGACY_KEY_LANGUAGE, null) ?: return
        // A value already saved in the main store wins - it is what the rest of the app has shown.
        if (!prefs.isLanguageSet() && legacyValue.isNotBlank()) prefs.setLanguage(legacyValue)
        legacy.edit().remove(LEGACY_KEY_LANGUAGE).apply()
    }

    fun updateResources(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
