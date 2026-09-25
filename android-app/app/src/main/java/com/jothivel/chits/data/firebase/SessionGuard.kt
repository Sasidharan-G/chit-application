package com.jothivel.chits.data.firebase

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One login, one phone. Each account (the admin, and every agent) owns a `sessions/{uid}` record naming
 * the phone that is logged in and when it was last seen. A login is refused while ANOTHER phone's record
 * is live; logging out deletes the record. A phone that stops checking in (switched off, uninstalled,
 * app closed) frees the account after [LIVE_WINDOW_MS], and a phone that finds another one has taken
 * over is logged out at its next check-in - so two phones are never in use at the same time.
 *
 * Offline is never a reason to refuse a login (the cloud cannot be asked); the conflict is found at the
 * first check-in once the phone is online again.
 */
object SessionGuard {
    private const val TAG = "SessionGuard"
    private const val COLLECTION = "sessions"
    private const val PREFS = "jvc_device"
    private const val KEY_DEVICE_ID = "device_id"
    private const val NETWORK_TIMEOUT_MS = 8_000L

    /** A phone that has not checked in for this long no longer holds its account. */
    const val LIVE_WINDOW_MS = 30 * 60 * 1000L
    /** How often a logged-in phone checks in (see MainHostActivity). */
    const val HEARTBEAT_MS = 60 * 1000L

    data class SessionRecord(val deviceId: String, val deviceName: String, val lastSeenMs: Long)

    sealed class Claim {
        /** This phone holds the account now (or already did). */
        object Held : Claim()
        /** Another phone is live on it. */
        data class Blocked(val deviceName: String) : Claim() {
            val message: String get() = "Already logged in on another phone ($deviceName). Log out there first."
        }
        /** The cloud could not be reached, so nothing is enforced. */
        object Unknown : Claim()
    }

    sealed class Beat {
        object Ok : Beat()
        data class Lost(val message: String) : Beat()
        object Unknown : Beat()
    }

    /** Who may hold the account: pure, so the rule is testable without Firestore. */
    internal fun decide(existing: SessionRecord?, myDeviceId: String, now: Long): Claim = when {
        existing == null -> Claim.Held
        existing.deviceId == myDeviceId -> Claim.Held
        now - existing.lastSeenMs >= LIVE_WINDOW_MS -> Claim.Held
        else -> Claim.Blocked(existing.deviceName.ifBlank { "another phone" })
    }

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(60).ifBlank { "Android phone" }

    /** Takes the signed-in account for this phone unless another phone is live on it. */
    suspend fun claim(context: Context): Claim {
        val firestore = FirebaseSetup.firestoreOrNull(context) ?: return Claim.Unknown
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Claim.Unknown
        val ref = firestore.collection(COLLECTION).document(uid)
        val me = deviceId(context)
        return try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                firestore.runTransaction { tx ->
                    val snap = tx.get(ref)
                    val now = System.currentTimeMillis()
                    val existing = if (snap.exists()) SessionRecord(
                        deviceId = snap.getString("deviceId").orEmpty(),
                        deviceName = snap.getString("deviceName").orEmpty(),
                        lastSeenMs = snap.getTimestamp("lastSeen")?.toDate()?.time ?: now
                    ) else null
                    val result = decide(existing, me, now)
                    if (result is Claim.Held) {
                        tx.set(ref, mapOf("deviceId" to me, "deviceName" to deviceName(), "lastSeen" to FieldValue.serverTimestamp()))
                    }
                    result
                }.await()
            } ?: Claim.Unknown
        } catch (e: Exception) {
            Log.w(TAG, "claim failed", e)
            Claim.Unknown
        }
    }

    /** Frees the account on logout - only if this phone is the one holding it. Best effort. */
    suspend fun release(context: Context) {
        val firestore = FirebaseSetup.firestoreOrNull(context) ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = firestore.collection(COLLECTION).document(uid)
        val me = deviceId(context)
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                firestore.runTransaction { tx ->
                    val snap = tx.get(ref)
                    if (snap.exists() && snap.getString("deviceId") == me) tx.delete(ref)
                    Unit
                }.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
    }

    /** Periodic check-in of a logged-in phone: keeps the account, or reports that another phone took it. */
    suspend fun heartbeat(context: Context): Beat {
        if (FirebaseSetup.firestoreIfSignedIn(context) == null) return Beat.Unknown
        return when (claim(context)) {
            is Claim.Held -> Beat.Ok
            is Claim.Blocked -> Beat.Lost("Logged out: this account is now open on another phone.")
            Claim.Unknown -> Beat.Unknown
        }
    }

    /**
     * For the admin's PIN login (called from Java, on a worker thread): the message to show when the
     * admin is already live on another phone, or null to let the login go on. Signs in to the cloud first;
     * no Cloud account, no internet or any cloud problem means no blocking.
     */
    @JvmStatic
    fun adminLoginBlockMessage(context: Context): String? = runBlocking(Dispatchers.IO) {
        if (!CloudAccount.isConfigured(context)) return@runBlocking null
        val signIn = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { FirebaseSetup.connectAdmin(context) }
        if (signIn !is FirebaseSetup.AdminSignIn.Connected) return@runBlocking null
        val blocked = claim(context) as? Claim.Blocked ?: return@runBlocking null
        FirebaseSetup.signOut()
        blocked.message
    }
}
