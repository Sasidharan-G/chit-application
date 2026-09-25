package com.jothivel.chits.data.firebase

import android.content.Context
import android.content.SharedPreferences

/**
 * The admin's Firebase Auth email + password, typed once on each phone in Settings > Cloud account and
 * kept in EncryptedSharedPreferences only (never in a plain preferences file; if the encrypted store
 * cannot be opened the credentials live in memory for this process). There is no password built into
 * the app: a new phone has to be given both. The admin app signs in with them to reach Firestore - the
 * old anonymous sign-in gave every APK the same access as the admin.
 */
object CloudAccount {
    private const val FILE = "JvcCloudAccountSecure"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"

    @Volatile private var memoryEmail: String? = null
    @Volatile private var memoryPassword: String? = null
    @Volatile private var cached: SharedPreferences? = null
    @Volatile private var triedOpen = false

    private fun prefs(context: Context): SharedPreferences? {
        if (triedOpen) return cached
        synchronized(this) {
            if (!triedOpen) {
                cached = SecurePrefs.open(context, FILE)
                triedOpen = true
            }
        }
        return cached
    }

    fun email(context: Context): String? = prefs(context)?.getString(KEY_EMAIL, null) ?: memoryEmail
    fun password(context: Context): String? = prefs(context)?.getString(KEY_PASSWORD, null) ?: memoryPassword
    fun isConfigured(context: Context): Boolean = !email(context).isNullOrBlank() && !password(context).isNullOrBlank()

    fun save(context: Context, email: String, password: String) {
        // A different cloud account is a different database: what was "already pushed" no longer holds.
        if (!email(context).equals(email.trim(), ignoreCase = true)) CloudPushLedger.clear(context)
        val p = prefs(context)
        if (p != null) p.edit().putString(KEY_EMAIL, email.trim()).putString(KEY_PASSWORD, password).apply()
        else {
            memoryEmail = email.trim()
            memoryPassword = password
        }
    }

    fun clear(context: Context) {
        memoryEmail = null
        memoryPassword = null
        prefs(context)?.edit()?.clear()?.apply()
        CloudPushLedger.clear(context)
    }
}
