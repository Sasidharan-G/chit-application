package com.jothivel.chits.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Changes the admin's Firebase Auth password from [oldPassword] to [newPassword]. */
fun interface CloudPasswordChanger {
    suspend fun change(context: Context, email: String, oldPassword: String, newPassword: String): Result<Unit>
}

/**
 * The app has one admin, and the admin's login PIN is also the Cloud account password: the Firebase
 * password is derived from the PIN (Firebase needs at least 6 characters), the same way an agent's is.
 * So Change PIN must change the Firebase password too, and a Cloud account connected with an older,
 * separate password is moved over to the PIN the next time the admin logs in.
 */
object AdminPin {
    private const val TAG = "AdminPin"
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The Firebase Auth password for an admin PIN. */
    fun cloudPassword(pin: String): String = "JVC#$pin"

    sealed class ChangeResult {
        object Changed : ChangeResult()
        object WrongCurrentPin : ChangeResult()
        object InvalidNewPin : ChangeResult()
        /** The cloud password could not be changed, so the PIN was left as it was. */
        data class CloudFailed(val message: String) : ChangeResult()
    }

    /**
     * Changes the login PIN and, when a Cloud account is connected, the cloud password with it. The
     * cloud is changed first: if that fails (no internet, say) nothing changes, so the PIN and the
     * cloud password never drift apart.
     */
    suspend fun changePin(
        context: Context,
        currentPin: String,
        newPin: String,
        cloud: CloudPasswordChanger = FirebasePasswordChanger
    ): ChangeResult {
        val prefs = AppPreferences(context)
        if (!prefs.verifyPin(currentPin)) return ChangeResult.WrongCurrentPin
        if (newPin.length != 4 || !newPin.all(Char::isDigit)) return ChangeResult.InvalidNewPin
        val email = CloudAccount.email(context)
        val storedPassword = CloudAccount.password(context)
        if (!email.isNullOrBlank() && !storedPassword.isNullOrBlank()) {
            val newPassword = cloudPassword(newPin)
            if (storedPassword != newPassword) {
                val result = cloud.change(context, email, storedPassword, newPassword)
                result.exceptionOrNull()?.let { return ChangeResult.CloudFailed(it.message ?: "Cloud password could not be changed.") }
                CloudAccount.save(context, email, newPassword)
            }
        }
        prefs.savePin(newPin)
        return ChangeResult.Changed
    }

    /**
     * After a correct PIN login: if the connected Cloud account still uses another password (the one
     * first typed in Settings), change it to this PIN. Returns true when nothing is left to do.
     */
    suspend fun alignCloudPassword(context: Context, pin: String, cloud: CloudPasswordChanger = FirebasePasswordChanger): Boolean {
        val email = CloudAccount.email(context)
        val storedPassword = CloudAccount.password(context)
        if (email.isNullOrBlank() || storedPassword.isNullOrBlank()) return true
        val newPassword = cloudPassword(pin)
        if (storedPassword == newPassword) return true
        val result = cloud.change(context, email, storedPassword, newPassword)
        if (result.isSuccess) CloudAccount.save(context, email, newPassword)
        else Log.w(TAG, "Cloud password not moved to the PIN yet; will retry at the next login", result.exceptionOrNull())
        return result.isSuccess
    }

    /** Fire-and-forget [alignCloudPassword] for the login screen (Java). */
    @JvmStatic
    fun alignCloudPasswordInBackground(context: Context, pin: String) {
        val app = context.applicationContext
        background.launch { runCatching { alignCloudPassword(app, pin) } }
    }
}

/** Signs in (or re-authenticates) with the old password, then sets the new one. */
object FirebasePasswordChanger : CloudPasswordChanger {
    override suspend fun change(context: Context, email: String, oldPassword: String, newPassword: String): Result<Unit> {
        if (!FirebaseSetup.ensureInitialized(context)) return Result.failure(IllegalStateException("Firebase is not configured on this install."))
        return try {
            val auth = FirebaseAuth.getInstance()
            val current = auth.currentUser
            val user = if (current != null && !current.isAnonymous && email.equals(current.email, ignoreCase = true)) {
                // Changing a password needs a recent sign-in.
                current.reauthenticate(EmailAuthProvider.getCredential(email, oldPassword)).await()
                current
            } else {
                auth.signOut()
                auth.signInWithEmailAndPassword(email, oldPassword).await().user
                    ?: return Result.failure(IllegalStateException("Sign-in did not return a user."))
            }
            user.updatePassword(newPassword).await()
            Result.success(Unit)
        } catch (e: FirebaseNetworkException) {
            Result.failure(IllegalStateException("No internet connection. Connect to the internet and try again."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(IllegalStateException("The saved cloud password is no longer valid. Reconnect Settings > Cloud account."))
        } catch (e: FirebaseTooManyRequestsException) {
            Result.failure(IllegalStateException("Too many attempts. Try again in a few minutes."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "Cloud password could not be changed."))
        }
    }
}
