package com.jothivel.chits.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.withTimeoutOrNull

/** The admin's PIN as saved in the cloud: the salted hash (never the PIN) and when it was changed. */
data class CloudPin(val pinHash: String, val changedAt: Long)

/** Where the shared PIN lives. Firestore in the app, a fake in the tests. */
interface PinStore {
    /** Null when no PIN has been saved yet; throws when the cloud cannot be reached or signed in to. */
    suspend fun read(context: Context): CloudPin?
    suspend fun write(context: Context, pin: CloudPin)
}

/**
 * One admin PIN for every phone. The PIN is kept on each phone (so login works offline) and, as a
 * salted hash, in the cloud at `adminPins/{uid}`. A change made on one phone is saved to the cloud as
 * soon as that phone is online and is picked up by the others; a phone that joins the account takes
 * over the PIN with the existing PIN plus the cloud email + password. The newest change wins.
 */
object AdminPinSync {
    private const val TAG = "AdminPinSync"
    private const val NETWORK_TIMEOUT_MS = 10_000L
    private const val HASH_PREFIX = "v2$"

    data class LocalPin(val hash: String, val changedAt: Long, val dirty: Boolean)

    enum class Action { NOTHING, PUSH, ADOPT, MARK_SYNCED }

    sealed class Result {
        object NotConfigured : Result()
        /** No internet, or the cloud account could not be used. Nothing is lost; it is retried later. */
        object Unreachable : Result()
        object UpToDate : Result()
        object Pushed : Result()
        /** The PIN was changed on another phone; this phone now uses it. */
        object Adopted : Result()
    }

    sealed class Restore {
        object Restored : Restore()
        object WrongPin : Restore()
        object NoCloudPin : Restore()
        object Unreachable : Restore()
    }

    /** What to do when this phone's PIN and the cloud's may differ. Pure, so the rule is testable. */
    internal fun decide(local: LocalPin?, cloud: CloudPin?): Action = when {
        local == null && cloud == null -> Action.NOTHING
        local == null -> Action.ADOPT
        cloud == null -> Action.PUSH
        local.hash == cloud.pinHash -> if (local.dirty || local.changedAt != cloud.changedAt) Action.MARK_SYNCED else Action.NOTHING
        local.dirty && local.changedAt > cloud.changedAt -> Action.PUSH
        cloud.changedAt > local.changedAt -> Action.ADOPT
        else -> Action.NOTHING
    }

    private fun localPin(prefs: AppPreferences): LocalPin? {
        val hash = prefs.getPin()
        // A PIN still stored in the old plain form is turned into a hash at the next successful login;
        // it is never sent anywhere.
        if (!hash.startsWith(HASH_PREFIX)) return null
        return LocalPin(hash, prefs.getPinChangedAt(), prefs.isPinDirty())
    }

    private class Answer(val pin: CloudPin?)

    /** Brings this phone and the cloud to the same PIN. Safe to call often; it never throws. */
    suspend fun sync(context: Context, store: PinStore = FirestorePinStore): Result {
        if (!CloudAccount.isConfigured(context)) return Result.NotConfigured
        val prefs = AppPreferences(context)
        val local = localPin(prefs)
        val cloud = try {
            (withTimeoutOrNull(NETWORK_TIMEOUT_MS) { Answer(store.read(context)) } ?: return Result.Unreachable).pin
        } catch (e: Exception) {
            Log.w(TAG, "read failed", e)
            return Result.Unreachable
        }
        return when (decide(local, cloud)) {
            Action.NOTHING -> Result.UpToDate
            Action.MARK_SYNCED -> { prefs.markPinSynced(cloud!!.changedAt); Result.UpToDate }
            Action.ADOPT -> { prefs.adoptPinHash(cloud!!.pinHash, cloud.changedAt); Result.Adopted }
            Action.PUSH -> push(context, prefs, local!!, store)
        }
    }

    private suspend fun push(context: Context, prefs: AppPreferences, local: LocalPin, store: PinStore): Result {
        // A PIN set before this feature has no change time yet: stamp it now.
        val changedAt = if (local.changedAt == 0L) System.currentTimeMillis() else local.changedAt
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) { store.write(context, CloudPin(local.hash, changedAt)) } ?: return Result.Unreachable
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            return Result.Unreachable
        }
        prefs.markPinSynced(changedAt)
        return Result.Pushed
    }

    /**
     * A phone that joins the account: after signing in with the admin email + password, checks the
     * existing PIN against the one saved in the cloud and, if it is right, takes it over.
     */
    suspend fun restoreOnNewPhone(context: Context, existingPin: String, store: PinStore = FirestorePinStore): Restore {
        val answer = try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) { Answer(store.read(context)) }
        } catch (e: Exception) {
            Log.w(TAG, "restore read failed", e)
            null
        } ?: return Restore.Unreachable
        val cloud = answer.pin ?: return Restore.NoCloudPin
        return restoreFrom(AppPreferences(context), existingPin, cloud)
    }

    internal fun restoreFrom(prefs: AppPreferences, existingPin: String, cloud: CloudPin): Restore {
        if (!AppPreferences.verifyPinHash(existingPin, cloud.pinHash)) return Restore.WrongPin
        prefs.adoptPinHash(cloud.pinHash, cloud.changedAt)
        return Restore.Restored
    }
}

/** `adminPins/{uid}` in Firestore, for whoever is signed in as the admin. */
object FirestorePinStore : PinStore {
    private const val COLLECTION = "adminPins"

    private suspend fun ref(context: Context) = run {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context) ?: throw IllegalStateException("Cloud account is not signed in.")
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: throw IllegalStateException("Cloud account is not signed in.")
        firestore.collection(COLLECTION).document(uid)
    }

    override suspend fun read(context: Context): CloudPin? {
        val snap = ref(context).get().await()
        if (!snap.exists()) return null
        val hash = snap.getString("pinHash") ?: return null
        return CloudPin(hash, snap.getLong("changedAt") ?: 0L)
    }

    override suspend fun write(context: Context, pin: CloudPin) {
        ref(context).set(mapOf("pinHash" to pin.pinHash, "changedAt" to pin.changedAt)).await()
    }
}
