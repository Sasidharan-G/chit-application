package com.jothivel.chits.data.firebase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The admin's Firebase Auth email + password, kept in EncryptedSharedPreferences only (never in a
 * plain preferences file; if the encrypted store cannot be opened the credentials live in memory for
 * this process). The admin app signs in with them to reach Firestore - the old anonymous sign-in gave
 * every APK the same access as the admin.
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
            if (triedOpen) return cached
            cached = open(context.applicationContext) ?: run {
                context.applicationContext.deleteSharedPreferences(FILE)
                open(context.applicationContext)
            }
            triedOpen = true
        }
        return cached
    }

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    fun email(context: Context): String? = prefs(context)?.getString(KEY_EMAIL, null) ?: memoryEmail
    fun password(context: Context): String? = prefs(context)?.getString(KEY_PASSWORD, null) ?: memoryPassword
    fun isConfigured(context: Context): Boolean = !email(context).isNullOrBlank() && !password(context).isNullOrBlank()

    fun save(context: Context, email: String, password: String) {
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
    }
}
