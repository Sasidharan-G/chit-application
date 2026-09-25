package com.jothivel.chits.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.jothivel.chits.utils.AppPreferences

/**
 * Central, crash-safe entry point to Firebase. The Labour/Agent feature must keep working
 * (in admin-only mode) on installs that don't have app/google-services.json yet, so every
 * caller goes through [firestoreOrNull] instead of calling FirebaseFirestore.getInstance() directly.
 *
 * Identity: nobody is anonymous any more. The ADMIN signs in with the email/password saved in
 * [CloudAccount] and must have a document in `admins/{uid}`; an AGENT is signed in by
 * [AgentAuthRepository.login] (Firebase Auth checks their PIN) and must have an active `agents/{uid}`.
 * Firestore's security rules key off exactly those two facts - see firestore.rules.
 */
object FirebaseSetup {
    private const val TAG = "FirebaseSetup"

    @Volatile private var initialized = false
    @Volatile private var configured = true
    @Volatile private var verifiedAdminUid: String? = null

    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return configured
        synchronized(this) {
            if (initialized) return configured
            configured = try {
                val apps = FirebaseApp.getApps(context.applicationContext)
                val app = if (apps.isEmpty()) {
                    FirebaseApp.initializeApp(context.applicationContext)
                } else {
                    apps.first()
                }
                if (app == null) {
                    Log.w(TAG, "Firebase not configured. Add app/google-services.json to enable Labour sync.")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase not configured. Add app/google-services.json to enable Labour sync.", e)
                false
            }
            initialized = true
        }
        return configured
    }

    /** Returns null (instead of throwing) when Firebase isn't configured on this install. */
    fun firestoreOrNull(context: Context): FirebaseFirestore? {
        if (!ensureInitialized(context)) return null
        return try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "FirebaseFirestore.getInstance() failed", e)
            null
        }
    }

    /** Why the admin could not be connected, in words that can be shown to the user. */
    sealed class AdminSignIn {
        object Connected : AdminSignIn()
        object NotConfigured : AdminSignIn()
        /** [wrongPassword] is true when Firebase rejected the email/password pair itself. */
        data class Failed(val message: String, val wrongPassword: Boolean = false) : AdminSignIn()
    }

    /**
     * Signs the admin in (if not already) and confirms the account really is an admin by reading
     * `admins/{uid}`. Safe to call repeatedly - it does nothing when the right user is already signed in.
     */
    suspend fun connectAdmin(context: Context): AdminSignIn {
        val firestore = firestoreOrNull(context) ?: return AdminSignIn.Failed("Firebase is not configured on this install.")
        val email = CloudAccount.email(context)
        val password = CloudAccount.password(context)
        if (email.isNullOrBlank() || password.isNullOrBlank()) return AdminSignIn.NotConfigured
        val auth = FirebaseAuth.getInstance()
        return try {
            var user = auth.currentUser
            if (user == null || user.isAnonymous || !email.equals(user.email, ignoreCase = true)) {
                verifiedAdminUid = null
                auth.signOut()
                user = auth.signInWithEmailAndPassword(email, password).await().user
            }
            val uid = user?.uid ?: return AdminSignIn.Failed("Sign-in did not return a user.")
            if (verifiedAdminUid != uid) {
                val isAdmin = try {
                    firestore.collection("admins").document(uid).get().await().exists()
                } catch (e: Exception) {
                    false
                }
                if (!isAdmin) {
                    auth.signOut()
                    return AdminSignIn.Failed("This email is not an admin account.")
                }
                verifiedAdminUid = uid
            }
            AdminSignIn.Connected
        } catch (e: FirebaseAuthInvalidUserException) {
            AdminSignIn.Failed("No account with this email exists.")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            AdminSignIn.Failed("Wrong email or PIN.", wrongPassword = true)
        } catch (e: FirebaseTooManyRequestsException) {
            AdminSignIn.Failed("Too many attempts. Try again in a few minutes.")
        } catch (e: FirebaseNetworkException) {
            AdminSignIn.Failed("No internet connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Admin sign-in failed", e)
            AdminSignIn.Failed(e.message ?: "Sign-in failed.")
        }
    }

    /**
     * The Firestore instance for whoever is using this device - or null when they are not (or can no
     * longer be) authenticated, e.g. no internet on first sign-in, wrong password, or an agent whose
     * session is gone. Every real read/write path goes through this.
     */
    suspend fun firestoreIfSignedIn(context: Context): FirebaseFirestore? {
        val firestore = firestoreOrNull(context) ?: return null
        val prefs = AppPreferences(context)
        return if (prefs.isAgent()) {
            val user = FirebaseAuth.getInstance().currentUser
            // The signed-in Firebase user must be this device's agent (not left over from someone else).
            if (user != null && !user.isAnonymous && user.uid == prefs.getAgentId()) firestore else null
        } else {
            if (connectAdmin(context) is AdminSignIn.Connected) firestore else null
        }
    }

    /** Signs out of Firebase Auth (called on logout so the next person on this device starts clean). */
    fun signOut() {
        verifiedAdminUid = null
        runCatching { FirebaseAuth.getInstance().signOut() }
    }
}
