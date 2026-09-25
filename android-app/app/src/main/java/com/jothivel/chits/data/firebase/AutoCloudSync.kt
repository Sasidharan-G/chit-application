package com.jothivel.chits.data.firebase

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the admin's chit / customer changes to the cloud by itself, without spending the free Firestore
 * quota on every tap: changes are kept ready on the phone and go up together once [THRESHOLD] groups /
 * customers are waiting, and only the documents that changed are written (see [CloudPushLedger]).
 * Fewer waiting than that are sent by "Sync Data to Cloud" in Settings, which stays as it was.
 *
 * Runs while the admin app is open: a change to the chit tables schedules a check a few seconds later
 * (so a burst of edits counts once), and MainHostActivity re-checks now and then so a push that failed
 * for lack of internet is retried.
 */
object AutoCloudSync {
    private const val TAG = "AutoCloudSync"
    /** Groups / customers that must be waiting before an automatic push happens. */
    const val THRESHOLD = 5
    private const val DEBOUNCE_MS = 20_000L

    sealed class Outcome {
        object NotConfigured : Outcome()
        data class Waiting(val pending: Int) : Outcome()
        data class Pushed(val documents: Int) : Outcome()
        object Failed : Outcome()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watching = AtomicBoolean(false)
    @Volatile private var observer: InvalidationTracker.Observer? = null
    @Volatile private var watchedDb: AppDatabase? = null
    @Volatile private var scheduled: Job? = null

    fun shouldPush(pending: Int): Boolean = pending >= THRESHOLD

    fun start(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val db = AppDatabase.getDatabase(app)
        val obs = object : InvalidationTracker.Observer("members", "chit_groups", "chit_memberships", "installments") {
            override fun onInvalidated(tables: Set<String>) = requestCheck(app, DEBOUNCE_MS)
        }
        observer = obs
        watchedDb = db
        db.invalidationTracker.addObserver(obs)
        requestCheck(app, 0)
    }

    fun stop() {
        observer?.let { watchedDb?.invalidationTracker?.removeObserver(it) }
        observer = null
        watchedDb = null
        scheduled?.cancel()
        watching.set(false)
    }

    /** Checks after [delayMs]; a newer request replaces one that is still waiting. */
    fun requestCheck(context: Context, delayMs: Long = 0L) {
        val app = context.applicationContext
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(delayMs)
            check(app)
        }
    }

    /** Pushes the changed documents if enough are waiting. Never throws. */
    suspend fun check(context: Context): Outcome {
        if (!CloudAccount.isConfigured(context)) return Outcome.NotConfigured
        val pending = try {
            FirestoreDataSync.pendingChanges(context)
        } catch (e: Exception) {
            Log.w(TAG, "could not count pending changes", e)
            return Outcome.Failed
        }
        if (!shouldPush(pending)) return Outcome.Waiting(pending)
        val result = FirestoreDataSync.syncAllToCloud(context, onlyChanged = true)
        return result.fold(
            onSuccess = {
                AppPreferences(context).setLastCloudSyncAt()
                Outcome.Pushed(it.groups + it.members + it.memberships + it.installments)
            },
            onFailure = {
                Log.w(TAG, "automatic push failed; it is retried at the next check", it)
                Outcome.Failed
            }
        )
    }
}
