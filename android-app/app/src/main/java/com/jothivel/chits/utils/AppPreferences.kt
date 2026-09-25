package com.jothivel.chits.utils

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "jothivel_chits_prefs"
        private const val KEY_LOGIN_PIN = "login_pin"
        private const val KEY_PIN_CHANGED_AT = "pin_changed_at"
        private const val KEY_PIN_DIRTY = "pin_dirty"
        private const val KEY_IS_ADMIN_SETUP = "is_admin_setup"
        private const val KEY_ADMIN_NAME = "admin_name"
        private const val KEY_ADMIN_PHONE = "admin_phone"
        private const val KEY_ADMIN_USERNAME = "admin_username"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_CLOUD_SYNC_AT = "last_cloud_sync_at"
        private const val KEY_ADMIN_PIN_FAIL_COUNT = "admin_pin_fail_count"
        private const val KEY_ADMIN_PIN_FAIL_FIRST_AT = "admin_pin_fail_first_at"
        private const val ADMIN_PIN_ATTEMPT_LIMIT = 5
        private const val ADMIN_PIN_ATTEMPT_WINDOW_MS = 15 * 60 * 1000L

        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_AGENT = "AGENT"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_AGENT_ID = "agent_id"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_AGENT_PHONE = "agent_phone"
        private const val KEY_AGENT_PIN_HASH = "agent_pin_hash_cache"
        private const val KEY_AGENT_IS_ACTIVE = "agent_is_active_cache"
        private const val KEY_AGENT_ASSIGNED_GROUPS = "agent_assigned_groups_cache"

        /** Salted SHA-256 hash in the same "v2$salt$hash" format used for the admin PIN. */
        fun hashPin(pin: String): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "v2\$${Base64.encodeToString(salt, Base64.NO_WRAP)}\$${computeHash(pin, salt)}"
        }

        fun verifyPinHash(pin: String, stored: String): Boolean {
            if (stored.isBlank()) return false
            if (!stored.startsWith("v2$")) return MessageDigest.isEqual(stored.toByteArray(), pin.toByteArray())
            val parts = stored.split('$')
            if (parts.size != 3) return false
            val salt = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return false
            return MessageDigest.isEqual(parts[2].toByteArray(), computeHash(pin, salt).toByteArray())
        }

        private fun computeHash(pin: String, salt: ByteArray): String {
            var bytes = salt + pin.toByteArray(Charsets.UTF_8)
            repeat(20_000) { bytes = MessageDigest.getInstance("SHA-256").digest(bytes) }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    }

    /** True once a language has been explicitly chosen (as opposed to the "en" default). */
    fun isLanguageSet(): Boolean = prefs.contains(KEY_LANGUAGE)

    fun setLanguage(langCode: String) {
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
    }

    fun isAdminSetup(): Boolean {
        return prefs.getBoolean(KEY_IS_ADMIN_SETUP, false)
    }

    fun setAdminSetup(isSetup: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ADMIN_SETUP, isSetup).apply()
    }

    fun getPin(): String {
        return prefs.getString(KEY_LOGIN_PIN, "") ?: ""
    }

    /** True while no PIN of the admin's own has been set, i.e. the factory default is still in use. */
    fun isUsingDefaultPin(): Boolean = getPin().isBlank()

    fun savePin(newPin: String) {
        prefs.edit().putString(KEY_LOGIN_PIN, hashPin(newPin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = getPin()
        if (stored.isBlank()) return pin == "1234"
        if (!stored.startsWith("v2$")) {
            val valid = MessageDigest.isEqual(stored.toByteArray(), pin.toByteArray())
            if (valid) savePin(pin)
            return valid
        }
        return verifyPinHash(pin, stored)
    }

    /** Changes the admin PIN after verifying [currentPin] - the only in-app way to ever move the
     *  PIN off its default (see [verifyPin]'s "blank stored PIN accepts 1234" fallback). Returns
     *  false without changing anything if the current PIN is wrong or the new PIN isn't 4 digits.
     *
     *  With [share] the change is remembered as "not yet saved to the cloud" so it is sent there the
     *  next time this phone is online (see AdminPinSync). The very first PIN chosen on a fresh phone
     *  is not shared: a phone that joins an account adopts the PIN already saved in the cloud. */
    fun changePin(currentPin: String, newPin: String, share: Boolean = true): Boolean {
        if (!verifyPin(currentPin)) return false
        if (newPin.length != 4 || !newPin.all(Char::isDigit)) return false
        savePin(newPin)
        if (share) prefs.edit().putLong(KEY_PIN_CHANGED_AT, System.currentTimeMillis()).putBoolean(KEY_PIN_DIRTY, true).apply()
        return true
    }

    // ── Admin PIN shared through the cloud (AdminPinSync) ──────────────────
    /** When the PIN was last changed on purpose, on any phone (0 = never / only the first local PIN). */
    fun getPinChangedAt(): Long = prefs.getLong(KEY_PIN_CHANGED_AT, 0L)

    /** True while a PIN change has not reached the cloud yet. */
    fun isPinDirty(): Boolean = prefs.getBoolean(KEY_PIN_DIRTY, false)

    /** The change up to [changedAt] is now in the cloud. */
    fun markPinSynced(changedAt: Long) {
        prefs.edit().putLong(KEY_PIN_CHANGED_AT, changedAt).putBoolean(KEY_PIN_DIRTY, false).apply()
    }

    /** Takes over the salted PIN hash saved in the cloud, so the same PIN opens this phone too. */
    fun adoptPinHash(hash: String, changedAt: Long) {
        prefs.edit().putString(KEY_LOGIN_PIN, hash).putLong(KEY_PIN_CHANGED_AT, changedAt).putBoolean(KEY_PIN_DIRTY, false).apply()
    }

    /** Returns a "too many attempts" message if the admin PIN entry is currently throttled, else
     *  null. Client-side only (mirrors AgentAuthRepository's agent-PIN throttle) - slows down
     *  someone guessing through the UI, not a substitute for the hash strength itself. */
    fun adminPinThrottleMessage(): String? {
        val count = prefs.getInt(KEY_ADMIN_PIN_FAIL_COUNT, 0)
        val firstAttempt = prefs.getLong(KEY_ADMIN_PIN_FAIL_FIRST_AT, 0L)
        val elapsed = System.currentTimeMillis() - firstAttempt
        if (count >= ADMIN_PIN_ATTEMPT_LIMIT && elapsed < ADMIN_PIN_ATTEMPT_WINDOW_MS) {
            val retryAfterMinutes = ((ADMIN_PIN_ATTEMPT_WINDOW_MS - elapsed) / 60000L) + 1
            return "Too many attempts. Try again in $retryAfterMinutes minute(s)."
        }
        return null
    }

    fun recordAdminPinFailure() {
        val now = System.currentTimeMillis()
        val firstAttempt = prefs.getLong(KEY_ADMIN_PIN_FAIL_FIRST_AT, 0L)
        val count = prefs.getInt(KEY_ADMIN_PIN_FAIL_COUNT, 0)
        if (firstAttempt == 0L || now - firstAttempt >= ADMIN_PIN_ATTEMPT_WINDOW_MS) {
            prefs.edit().putInt(KEY_ADMIN_PIN_FAIL_COUNT, 1).putLong(KEY_ADMIN_PIN_FAIL_FIRST_AT, now).apply()
        } else {
            prefs.edit().putInt(KEY_ADMIN_PIN_FAIL_COUNT, count + 1).apply()
        }
    }

    fun clearAdminPinFailures() {
        prefs.edit().remove(KEY_ADMIN_PIN_FAIL_COUNT).remove(KEY_ADMIN_PIN_FAIL_FIRST_AT).apply()
    }

    fun saveAdminProfile(name: String, phone: String, username: String) {
        prefs.edit()
            .putString(KEY_ADMIN_NAME, name)
            .putString(KEY_ADMIN_PHONE, phone)
            .putString(KEY_ADMIN_USERNAME, username)
            .apply()
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, loggedIn).apply()
    }

    fun setLastBackupAt(value: Long = System.currentTimeMillis()) { prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply() }
    fun getLastBackupAt(): Long = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
    fun setLastCloudSyncAt(value: Long = System.currentTimeMillis()) { prefs.edit().putLong(KEY_LAST_CLOUD_SYNC_AT, value).apply() }
    fun getLastCloudSyncAt(): Long = prefs.getLong(KEY_LAST_CLOUD_SYNC_AT, 0L)

    // ── Role / Labour (field agent) session ──────────────────────────────
    fun getUserRole(): String = prefs.getString(KEY_USER_ROLE, ROLE_ADMIN) ?: ROLE_ADMIN
    fun isAgent(): Boolean = getUserRole() == ROLE_AGENT

    fun getAgentId(): String = prefs.getString(KEY_AGENT_ID, "") ?: ""
    fun getAgentName(): String = prefs.getString(KEY_AGENT_NAME, "") ?: ""
    fun getAgentPhone(): String = prefs.getString(KEY_AGENT_PHONE, "") ?: ""
    fun getAgentAssignedGroups(): List<String> =
        (prefs.getString(KEY_AGENT_ASSIGNED_GROUPS, "") ?: "").split(',').filter { it.isNotBlank() }
    fun isAgentActiveCached(): Boolean = prefs.getBoolean(KEY_AGENT_IS_ACTIVE, true)

    /** Persists the agent's identity + cached credentials so login works offline afterwards. */
    fun saveAgentSession(agentId: String, name: String, phone: String, pinHash: String, isActive: Boolean, assignedGroups: List<String>) {
        prefs.edit()
            .putString(KEY_USER_ROLE, ROLE_AGENT)
            .putString(KEY_AGENT_ID, agentId)
            .putString(KEY_AGENT_NAME, name)
            .putString(KEY_AGENT_PHONE, phone)
            .putString(KEY_AGENT_PIN_HASH, pinHash)
            .putBoolean(KEY_AGENT_IS_ACTIVE, isActive)
            .putString(KEY_AGENT_ASSIGNED_GROUPS, assignedGroups.joinToString(","))
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    /** Cached PIN hash used to allow agent login while offline; refreshed on every successful online login. */
    fun getCachedAgentPinHash(): String = prefs.getString(KEY_AGENT_PIN_HASH, "") ?: ""

    fun clearAgentSession() {
        prefs.edit()
            .remove(KEY_USER_ROLE)
            .remove(KEY_AGENT_ID)
            .remove(KEY_AGENT_NAME)
            .remove(KEY_AGENT_PHONE)
            .remove(KEY_AGENT_PIN_HASH)
            .remove(KEY_AGENT_IS_ACTIVE)
            .remove(KEY_AGENT_ASSIGNED_GROUPS)
            .apply()
    }
}
