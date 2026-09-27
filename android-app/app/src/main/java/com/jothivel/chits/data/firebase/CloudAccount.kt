package com.jothivel.chits.data.firebase

import android.content.Context

/**
 * Whether cloud sync is turned ON for THIS phone. The account itself is fixed at build time
 * ([AdminAccount]) - nothing is typed here any more - so this is just an on/off switch: ON by default
 * (a fresh install works with the cloud immediately, no Settings step needed), and OFF only after the
 * admin explicitly taps "Disconnect this phone" in Settings > Cloud account.
 */
object CloudAccount {
    private const val PREFS = "jvc_cloud_enabled"
    private const val KEY_ENABLED = "enabled"

    fun email(context: Context): String? = AdminAccount.EMAIL.takeIf { it.isNotBlank() }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Whether cloud features may run on this phone: the build has an account, and it hasn't been switched off. */
    fun isConfigured(context: Context): Boolean = AdminAccount.hasCredentials && isEnabled(context)
}
