package com.jothivel.chits.data.firebase

import android.content.Context
import com.jothivel.chits.BuildConfig

/**
 * The ONE Firebase admin account this app is built for (android-app/cloud.properties, compiled in as
 * BuildConfig.CLOUD_EMAIL / CLOUD_PASSWORD - see that file). No phone ever types an email or password:
 * every install shares the same account, and [restoreWithPin] is how a reinstalled or new admin phone
 * gets back the login PIN already in use, by proving it knows that PIN.
 */
object AdminAccount {
    val EMAIL: String get() = BuildConfig.CLOUD_EMAIL
    val PASSWORD: String get() = BuildConfig.CLOUD_PASSWORD

    /** False only for a build made without cloud.properties - cloud features cannot work then. */
    val hasCredentials: Boolean get() = EMAIL.isNotBlank() && PASSWORD.isNotBlank()

    sealed class RestoreOutcome {
        /** This phone now uses the PIN saved in the cloud, and is signed in to the cloud account. */
        object Restored : RestoreOutcome()
        object WrongPin : RestoreOutcome()
        /** Nobody has ever set a PIN in the cloud yet - there is nothing to restore. */
        object NoCloudPin : RestoreOutcome()
        object Unreachable : RestoreOutcome()
        data class ConnectFailed(val message: String) : RestoreOutcome()
    }

    /**
     * Signs in to the fixed cloud account and, if [pin] matches the one already saved there, makes it
     * this phone's login PIN too (see [AdminPinSync.restoreOnNewPhone]). Used both by the very first
     * "Set your PIN" screen ("I already use this app on another phone") and by a plain "Already a user?"
     * link on the daily PIN login screen, so a reinstalled phone never needs a brand new PIN.
     */
    suspend fun restoreWithPin(context: Context, pin: String): RestoreOutcome {
        if (!hasCredentials) return RestoreOutcome.ConnectFailed("This build has no cloud account configured.")
        CloudAccount.setEnabled(context, true)
        return when (val signIn = FirebaseSetup.connectAdmin(context)) {
            is FirebaseSetup.AdminSignIn.Connected -> when (AdminPinSync.restoreOnNewPhone(context, pin)) {
                AdminPinSync.Restore.Restored -> {
                    // Proof of the PIN is proof enough: take this phone's session over even if the
                    // cloud still thinks another one (possibly this very phone before a reinstall) is
                    // live, instead of the admin getting locked out right after restoring.
                    SessionGuard.forceClaim(context)
                    FirebaseSyncService.start(context)
                    AutoCloudSync.requestCheck(context)
                    RestoreOutcome.Restored
                }
                AdminPinSync.Restore.WrongPin -> RestoreOutcome.WrongPin
                AdminPinSync.Restore.NoCloudPin -> RestoreOutcome.NoCloudPin
                AdminPinSync.Restore.Unreachable -> RestoreOutcome.Unreachable
            }
            is FirebaseSetup.AdminSignIn.Failed -> RestoreOutcome.ConnectFailed(signIn.message)
            FirebaseSetup.AdminSignIn.NotConfigured -> RestoreOutcome.ConnectFailed("Cloud sync is off for this phone.")
        }
    }
}
