package com.jothivel.chits.data.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FieldValue
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed class AgentLoginResult {
    data class Success(val agentId: String, val name: String, val assignedGroups: List<String>) : AgentLoginResult()
    data class Failure(val message: String) : AgentLoginResult()
}

data class AgentSummary(
    val id: String,
    val name: String,
    val phone: String,
    val isActive: Boolean,
    val assignedGroups: List<String>
)

/**
 * Firebase-backed identity for field collection agents (the "Labour" feature).
 *
 * Every agent is a Firebase Auth email/password user: the email is derived from their mobile number
 * and the password from their PIN, so Firebase itself checks the PIN and rate-limits wrong guesses.
 * Nothing that could be used to crack a PIN (no hash) is stored in Firestore. The agent's Auth UID is
 * also the id of their `agents/{uid}` document, which is what the security rules key off.
 *
 * Firebase cannot change another user's password from a client, so "reset PIN" creates the next
 * generation of the account (`...g1@...`) and moves the agent record to it; login tries the newest
 * generation first and any stale one has no `agents` document and is refused.
 */
object AgentAuthRepository {

    private const val LOGIN_ATTEMPTS_PREFS = "jvc_agent_login_attempts"
    private const val GEN_PREFS = "jvc_agent_auth_gen"
    private const val LOGIN_ATTEMPT_LIMIT = 5
    private const val LOGIN_ATTEMPT_WINDOW_MS = 15 * 60 * 1000L
    private const val GENERIC_LOGIN_FAILURE = "Wrong mobile number or PIN."
    private const val ONLINE_TIMEOUT_MS = 15_000L
    internal const val MAX_GEN = 3
    private const val SECONDARY_APP = "agent-creator"

    /** The Auth email for an agent's phone number and account generation. */
    internal fun agentEmail(phone: String, gen: Int): String = "a${phone.filter(Char::isDigit)}g$gen@agents.jothivel-chits.app"

    /** Firebase needs at least 6 characters, so a 4-digit PIN is prefixed rather than rejected. */
    internal fun agentPassword(pin: String): String = "JVC#$pin"

    /** Generations to try for a login: the last one that worked first, then the rest newest-first. */
    internal fun generationOrder(lastKnown: Int?): List<Int> {
        val all = (MAX_GEN downTo 0).toList()
        return if (lastKnown != null && lastKnown in all) listOf(lastKnown) + all.filter { it != lastKnown } else all
    }

    /**
     * Online first: Firebase checks the phone + PIN. With no internet (or a connection so slow it
     * gives up after [ONLINE_TIMEOUT_MS]) the PIN is checked against the copy saved on this phone by
     * the last successful online login, so a bad signal never leaves the login spinning.
     */
    suspend fun login(context: Context, phone: String, pin: String): AgentLoginResult {
        val prefs = AppPreferences(context)
        val throttled = throttleMessage(context, phone)
        if (throttled != null) return AgentLoginResult.Failure(throttled)
        return withTimeoutOrNull(ONLINE_TIMEOUT_MS) { loginOnline(context, prefs, phone, pin) }
            ?: loginFromCache(context, prefs, phone, pin)
    }

    private suspend fun loginOnline(context: Context, prefs: AppPreferences, phone: String, pin: String): AgentLoginResult {
        val firestore = FirebaseSetup.firestoreOrNull(context) ?: return loginFromCache(context, prefs, phone, pin)
        val auth = FirebaseAuth.getInstance()
        val genPrefs = context.getSharedPreferences(GEN_PREFS, Context.MODE_PRIVATE)
        val lastKnown = genPrefs.getInt(phone, -1).takeIf { it >= 0 }
        try {
            for (gen in generationOrder(lastKnown)) {
                val uid = try {
                    auth.signInWithEmailAndPassword(agentEmail(phone, gen), agentPassword(pin)).await().user?.uid
                } catch (e: FirebaseAuthInvalidUserException) {
                    null // no account for this generation
                } catch (e: FirebaseAuthInvalidCredentialsException) {
                    null // wrong PIN for this generation (or it does not exist)
                } ?: continue

                val doc = firestore.collection(FirestoreSchema.AGENTS).document(uid).get().await()
                if (!doc.exists()) { // a superseded generation: the record moved to a newer account
                    auth.signOut()
                    continue
                }
                if (doc.getBoolean(FirestoreSchema.Agent.IS_ACTIVE) != true) {
                    auth.signOut()
                    return AgentLoginResult.Failure("Your account has been disconnected. Contact admin.")
                }
                val name = doc.getString(FirestoreSchema.Agent.NAME).orEmpty()
                @Suppress("UNCHECKED_CAST")
                val assignedGroups = (doc.get(FirestoreSchema.Agent.ASSIGNED_GROUPS) as? List<String>).orEmpty()
                // The hash below is only a local convenience so this phone can open the app offline;
                // it never leaves the device.
                prefs.saveAgentSession(uid, name, phone, AppPreferences.hashPin(pin), true, assignedGroups)
                genPrefs.edit().putInt(phone, gen).apply()
                clearLoginFailures(context, phone)
                return AgentLoginResult.Success(uid, name, assignedGroups)
            }
            recordLoginFailure(context, phone)
            return AgentLoginResult.Failure(GENERIC_LOGIN_FAILURE)
        } catch (e: FirebaseTooManyRequestsException) {
            return AgentLoginResult.Failure("Too many attempts. Try again in a few minutes.")
        } catch (e: FirebaseNetworkException) {
            return loginFromCache(context, prefs, phone, pin)
        } catch (e: Exception) {
            return loginFromCache(context, prefs, phone, pin)
        }
    }

    /**
     * Live check of the *currently logged-in* agent's isActive flag, used to revoke access mid-session -
     * not just at the next login - when the admin deactivates them. On success it also refreshes the
     * cached name and assigned groups. Returns null (never act on it) when the check couldn't be
     * performed - offline, no session, or a Firestore error - so a network hiccup never forces a false logout.
     */
    suspend fun refreshAndCheckActive(context: Context): Boolean? {
        val prefs = AppPreferences(context)
        val agentId = prefs.getAgentId()
        if (agentId.isBlank()) return null
        val firestore = FirebaseSetup.firestoreIfSignedIn(context) ?: return null
        return try {
            val doc = firestore.collection(FirestoreSchema.AGENTS).document(agentId).get().await()
            if (!doc.exists()) return false
            val isActive = doc.getBoolean(FirestoreSchema.Agent.IS_ACTIVE) ?: false
            if (isActive) {
                @Suppress("UNCHECKED_CAST")
                val assignedGroups = (doc.get(FirestoreSchema.Agent.ASSIGNED_GROUPS) as? List<String>).orEmpty()
                val name = doc.getString(FirestoreSchema.Agent.NAME)?.takeIf { it.isNotBlank() } ?: prefs.getAgentName()
                prefs.saveAgentSession(agentId, name, prefs.getAgentPhone(), prefs.getCachedAgentPinHash(), true, assignedGroups)
            }
            isActive
        } catch (e: Exception) {
            null
        }
    }

    /** No internet: only a phone that already logged this agent in online once can open the app. */
    internal fun loginFromCache(context: Context, prefs: AppPreferences, phone: String, pin: String): AgentLoginResult {
        if (prefs.getAgentPhone() != phone || prefs.getAgentId().isBlank()) {
            return AgentLoginResult.Failure("No internet. Log in once with internet on this phone first.")
        }
        if (!prefs.isAgentActiveCached()) return AgentLoginResult.Failure("Your account has been disconnected. Contact admin.")
        if (!AppPreferences.verifyPinHash(pin, prefs.getCachedAgentPinHash())) {
            recordLoginFailure(context, phone)
            return AgentLoginResult.Failure(GENERIC_LOGIN_FAILURE)
        }
        clearLoginFailures(context, phone)
        return AgentLoginResult.Success(prefs.getAgentId(), prefs.getAgentName(), prefs.getAgentAssignedGroups())
    }

    // ── Login attempt throttling (per phone number, SharedPreferences-backed) ────────────────
    // A convenience on top of Firebase's own server-side rate limiting.

    private fun throttleMessage(context: Context, phone: String): String? {
        val entry = readAttempts(context).optJSONObject(phone) ?: return null
        val count = entry.optInt("count", 0)
        val firstAttempt = entry.optLong("firstAttempt", 0L)
        val elapsed = System.currentTimeMillis() - firstAttempt
        if (count >= LOGIN_ATTEMPT_LIMIT && elapsed < LOGIN_ATTEMPT_WINDOW_MS) {
            val retryAfterMinutes = ((LOGIN_ATTEMPT_WINDOW_MS - elapsed) / 60000L) + 1
            return "Too many attempts. Try again in $retryAfterMinutes minute(s)."
        }
        return null
    }

    private fun recordLoginFailure(context: Context, phone: String) {
        val root = readAttempts(context)
        val now = System.currentTimeMillis()
        val existing = root.optJSONObject(phone)
        val firstAttempt = existing?.optLong("firstAttempt", 0L) ?: 0L
        val stillInWindow = existing != null && (now - firstAttempt) < LOGIN_ATTEMPT_WINDOW_MS
        val entry = JSONObject()
        if (stillInWindow) {
            entry.put("count", (existing?.optInt("count", 0) ?: 0) + 1)
            entry.put("firstAttempt", firstAttempt)
        } else {
            entry.put("count", 1)
            entry.put("firstAttempt", now)
        }
        root.put(phone, entry)
        writeAttempts(context, root)
    }

    private fun clearLoginFailures(context: Context, phone: String) {
        val root = readAttempts(context)
        if (root.has(phone)) {
            root.remove(phone)
            writeAttempts(context, root)
        }
    }

    private fun readAttempts(context: Context): JSONObject {
        val raw = context.getSharedPreferences(LOGIN_ATTEMPTS_PREFS, Context.MODE_PRIVATE).getString("attempts", null)
            ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun writeAttempts(context: Context, root: JSONObject) {
        context.getSharedPreferences(LOGIN_ATTEMPTS_PREFS, Context.MODE_PRIVATE)
            .edit().putString("attempts", root.toString()).apply()
    }

    // ── Admin-side management (Labour screen) ────────────────────────────

    suspend fun listAgents(context: Context): List<AgentSummary> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context) ?: return emptyList()
        val snapshot = firestore.collection(FirestoreSchema.AGENTS).get().await()
        return snapshot.documents.map { doc ->
            @Suppress("UNCHECKED_CAST")
            AgentSummary(
                id = doc.id,
                name = doc.getString(FirestoreSchema.Agent.NAME).orEmpty(),
                phone = doc.getString(FirestoreSchema.Agent.PHONE).orEmpty(),
                isActive = doc.getBoolean(FirestoreSchema.Agent.IS_ACTIVE) ?: false,
                assignedGroups = (doc.get(FirestoreSchema.Agent.ASSIGNED_GROUPS) as? List<String>).orEmpty()
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** A second Firebase app instance, so creating an agent's Auth user does not sign the admin out. */
    private fun creatorAuth(context: Context): FirebaseAuth {
        val primary = FirebaseApp.getInstance()
        val app = runCatching { FirebaseApp.getInstance(SECONDARY_APP) }.getOrNull()
            ?: FirebaseApp.initializeApp(context.applicationContext, primary.options, SECONDARY_APP)
        return FirebaseAuth.getInstance(app)
    }

    /** Creates the Auth user for the first free generation at or after [fromGen]; returns (uid, gen). */
    private suspend fun createAuthUser(context: Context, phone: String, pin: String, fromGen: Int): Pair<String, Int> {
        val auth = creatorAuth(context)
        for (gen in fromGen..MAX_GEN) {
            try {
                val uid = auth.createUserWithEmailAndPassword(agentEmail(phone, gen), agentPassword(pin)).await().user?.uid
                    ?: throw IllegalStateException("Could not create the login for this agent.")
                auth.signOut()
                return uid to gen
            } catch (e: FirebaseAuthUserCollisionException) {
                continue // that generation already exists - use the next one
            }
        }
        throw IllegalStateException("This phone number has been used for too many labour logins. Contact support.")
    }

    suspend fun createAgent(context: Context, name: String, phone: String, pin: String): Result<String> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account first."))
        return try {
            val existing = firestore.collection(FirestoreSchema.AGENTS).whereEqualTo(FirestoreSchema.Agent.PHONE, phone).limit(1).get().await()
            if (!existing.isEmpty) {
                return Result.failure(IllegalStateException("A labour account with this phone number already exists."))
            }
            val (uid, gen) = createAuthUser(context, phone, pin, 0)
            val data = hashMapOf(
                FirestoreSchema.Agent.NAME to name,
                FirestoreSchema.Agent.PHONE to phone,
                FirestoreSchema.Agent.IS_ACTIVE to true,
                FirestoreSchema.Agent.ASSIGNED_GROUPS to emptyList<String>(),
                FirestoreSchema.Agent.AUTH_EMAIL to agentEmail(phone, gen),
                FirestoreSchema.Agent.GEN to gen,
                FirestoreSchema.Agent.CREATED_AT to FieldValue.serverTimestamp(),
                FirestoreSchema.Agent.CREATED_BY to "admin"
            )
            firestore.collection(FirestoreSchema.AGENTS).document(uid).set(data).await()
            Result.success(uid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setActive(context: Context, agentId: String, isActive: Boolean): Result<Unit> =
        update(context, agentId, mapOf(FirestoreSchema.Agent.IS_ACTIVE to isActive))

    /** New PIN = next account generation; the agent record (and its groups) moves to the new UID. */
    suspend fun resetPin(context: Context, agentId: String, newPin: String): Result<Unit> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account first."))
        return try {
            val old = firestore.collection(FirestoreSchema.AGENTS).document(agentId).get().await()
            if (!old.exists()) return Result.failure(IllegalStateException("Agent not found."))
            val phone = old.getString(FirestoreSchema.Agent.PHONE).orEmpty()
            val currentGen = (old.getLong(FirestoreSchema.Agent.GEN) ?: 0L).toInt()
            val (newUid, gen) = createAuthUser(context, phone, newPin, currentGen + 1)
            @Suppress("UNCHECKED_CAST")
            val data = hashMapOf<String, Any?>(
                FirestoreSchema.Agent.NAME to old.getString(FirestoreSchema.Agent.NAME).orEmpty(),
                FirestoreSchema.Agent.PHONE to phone,
                FirestoreSchema.Agent.IS_ACTIVE to true,
                FirestoreSchema.Agent.ASSIGNED_GROUPS to ((old.get(FirestoreSchema.Agent.ASSIGNED_GROUPS) as? List<String>).orEmpty()),
                FirestoreSchema.Agent.AUTH_EMAIL to agentEmail(phone, gen),
                FirestoreSchema.Agent.GEN to gen,
                FirestoreSchema.Agent.CREATED_AT to FieldValue.serverTimestamp(),
                FirestoreSchema.Agent.CREATED_BY to "admin"
            )
            firestore.collection(FirestoreSchema.AGENTS).document(newUid).set(data).await()
            firestore.collection(FirestoreSchema.AGENTS).document(agentId).delete().await()
            refreshAccess(context, "PIN changed")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setAssignedGroups(context: Context, agentId: String, groupIds: List<String>): Result<Unit> =
        update(context, agentId, mapOf(FirestoreSchema.Agent.ASSIGNED_GROUPS to groupIds))

    suspend fun deleteAgent(context: Context, agentId: String): Result<Unit> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account first."))
        return try {
            firestore.collection(FirestoreSchema.AGENTS).document(agentId).delete().await()
            refreshAccess(context, "Agent removed")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun update(context: Context, agentId: String, fields: Map<String, Any>): Result<Unit> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account first."))
        return try {
            firestore.collection(FirestoreSchema.AGENTS).document(agentId).update(fields).await()
            refreshAccess(context, "Saved")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** After any change to who is assigned where, re-stamp the visibility lists on the cloud data. */
    private suspend fun refreshAccess(context: Context, doneLabel: String): Result<Unit> =
        FirestoreDataSync.refreshAgentAccess(context).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(IllegalStateException("$doneLabel, but agent access could not be refreshed (${it.message}). Press Sync to cloud to retry.")) }
        )
}
