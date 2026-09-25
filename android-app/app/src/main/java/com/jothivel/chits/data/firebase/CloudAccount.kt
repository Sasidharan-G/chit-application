package com.jothivel.chits.data.firebase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jothivel.chits.BuildConfig

/**
 * The admin's Firebase Auth account. The email is typed once in Settings and kept in
 * EncryptedSharedPreferences (in memory for this process if the encrypted store cannot be opened).
 * The password is the shared one built into the app ([BuildConfig.CLOUD_PASSWORD], from the git-ignored
 * cloud.properties) - the app never asks for it and never changes it. The admin app signs in with them
 * to reach Firestore; the old anonymous sign-in gave every APK the same access as the admin.
 */
object CloudAccount {
    private const val FILE = "JvcCloudAccountSecure"
    private const val KEY_EMAIL = "email"

    @Volatile private var memoryEmail: String? = null
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

    /** The shared cloud password; blank when this build was made without cloud.properties. */
    fun password(@Suppress("UNUSED_PARAMETER") context: Context): String = BuildConfig.CLOUD_PASSWORD

    /** False when the build has no cloud password - cloud features cannot work then. */
    fun hasPassword(): Boolean = BuildConfig.CLOUD_PASSWORD.isNotBlank()

    fun isConfigured(context: Context): Boolean = !email(context).isNullOrBlank() && hasPassword()

    fun save(context: Context, email: String) {
        val p = prefs(context)
        if (p != null) p.edit().putString(KEY_EMAIL, email.trim()).apply()
        else memoryEmail = email.trim()
    }

    fun clear(context: Context) {
        memoryEmail = null
        prefs(context)?.edit()?.clear()?.apply()
    }
}
