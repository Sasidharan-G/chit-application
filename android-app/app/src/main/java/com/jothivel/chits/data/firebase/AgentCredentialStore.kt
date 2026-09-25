package com.jothivel.chits.data.firebase

import android.content.Context
import android.content.SharedPreferences

/**
 * On the ADMIN's phone only: the Firebase login (account generation + PIN) of each agent this phone
 * created, kept in EncryptedSharedPreferences and never shown anywhere. With it, resetting an agent's PIN
 * changes the password of the SAME Firebase account and deleting an agent deletes it, so neither uses up
 * one of the few account generations a phone number has (see [AgentAuthRepository]). For agents created
 * before this existed, or on another admin phone, the store has nothing and the old way is used.
 */
object AgentCredentialStore {
    private const val FILE = "JvcAgentCredsSecure"

    data class Creds(val gen: Int, val pin: String)

    @Volatile private var cached: SharedPreferences? = null
    @Volatile private var triedOpen = false
    private val memory = HashMap<String, String>()

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

    fun get(context: Context, agentId: String): Creds? {
        val raw = prefs(context)?.getString(agentId, null) ?: synchronized(memory) { memory[agentId] } ?: return null
        val parts = raw.split(':', limit = 2)
        val gen = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val pin = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return Creds(gen, pin)
    }

    fun put(context: Context, agentId: String, gen: Int, pin: String) {
        val value = "$gen:$pin"
        val p = prefs(context)
        if (p != null) p.edit().putString(agentId, value).apply() else synchronized(memory) { memory[agentId] = value }
    }

    fun remove(context: Context, agentId: String) {
        synchronized(memory) { memory.remove(agentId) }
        prefs(context)?.edit()?.remove(agentId)?.apply()
    }

    fun clear(context: Context) {
        synchronized(memory) { memory.clear() }
        prefs(context)?.edit()?.clear()?.apply()
    }
}
