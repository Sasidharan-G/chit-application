package com.jothivel.chits.data.firebase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens an EncryptedSharedPreferences file, or null when the device's key store cannot be used (the
 * file is then deleted once and recreated; callers keep a memory copy for the current process).
 */
internal object SecurePrefs {
    fun open(context: Context, file: String): SharedPreferences? {
        val app = context.applicationContext
        return create(app, file) ?: run {
            app.deleteSharedPreferences(file)
            create(app, file)
        }
    }

    private fun create(context: Context, file: String): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, file, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
}
