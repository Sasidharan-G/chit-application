package com.jothivel.chits.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.CollectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admin-only real-time bridge: agent collections land in Firestore `collections/`, this listens
 * for the ones not yet applied (`syncedToAdmin == false`), mirrors each into the admin's local
 * Room DB via [CollectionService.record] (so the existing Ledger — which reads Room via
 * LiveData — updates automatically), then flags the Firestore doc as synced.
 *
 * It looks after itself: [start] is safe to call as often as you like (MainHostActivity calls it on
 * every check-in), it does nothing while a listener is alive, and it attaches one as soon as the cloud
 * account can be signed in to - so opening the app offline, or connecting the Cloud account later, no
 * longer needs a restart. A listener that dies (permission or network error) is dropped so the next
 * call replaces it, and [restart] re-reads the still-unapplied collections.
 */
object FirebaseSyncService {
    private const val TAG = "FirebaseSyncService"
    @Volatile private var registration: ListenerRegistration? = null
    private val starting = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (registration != null || !starting.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        // Firestore rules require an authenticated caller, and starting the listener before
        // sign-in completes would just fail every read with PERMISSION_DENIED - so sign in
        // (async) first, then attach the listener.
        scope.launch {
            try {
                val firestore = FirebaseSetup.firestoreIfSignedIn(appContext) ?: return@launch // retried by the next call
                if (registration != null) return@launch
                registration = firestore.collection(FirestoreSchema.COLLECTIONS)
                    .whereEqualTo(FirestoreSchema.Collection.SYNCED_TO_ADMIN, false)
                    .addSnapshotListener { snapshots, error ->
                        if (error != null || snapshots == null) {
                            if (error != null) {
                                Log.e(TAG, "Listener error - it will be re-attached at the next check-in", error)
                                drop()
                            }
                            return@addSnapshotListener
                        }
                        snapshots.documentChanges
                            .filter { it.type != DocumentChange.Type.REMOVED }
                            .forEach { change -> scope.launch { applyToRoom(appContext, firestore, change.document) } }
                    }
            } finally {
                starting.set(false)
            }
        }
    }

    /** Detaches and attaches again: the cloud re-sends every collection that is still not applied. */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    fun stop() = drop()

    private fun drop() {
        registration?.remove()
        registration = null
    }

    /**
     * Applies one agent collection to the admin's ledger and only THEN marks it synced. If applying
     * fails (customer not active here, chit unknown, ...) the document is left unsynced so it is retried
     * on the next [restart], instead of being flagged as applied and silently vanishing from the ledger.
     * The agent's receipt number and the time it was taken are kept.
     */
    private suspend fun applyToRoom(context: Context, firestore: FirebaseFirestore, doc: DocumentSnapshot) {
        try {
            val memberId = doc.getString(FirestoreSchema.Collection.MEMBER_ID)
            val groupId = doc.getString(FirestoreSchema.Collection.GROUP_ID)
            val amountPaise = doc.getLong(FirestoreSchema.Collection.AMOUNT_PAISE) ?: 0L
            if (memberId == null || groupId == null || amountPaise <= 0) {
                Log.e(TAG, "Collection ${doc.id} is malformed and was not applied")
                return
            }
            val db = AppDatabase.getDatabase(context)
            val requestId = doc.getString(FirestoreSchema.Collection.REQUEST_ID) ?: doc.id
            try {
                CollectionService.record(
                    db, requestId, memberId,
                    doc.getString(FirestoreSchema.Collection.MEMBER_NAME).orEmpty(), groupId, amountPaise,
                    doc.getString(FirestoreSchema.Collection.MODE) ?: "Cash",
                    doc.getString(FirestoreSchema.Collection.REFERENCE_NO),
                    doc.getString(FirestoreSchema.Collection.NOTES).orEmpty(),
                    doc.getString(FirestoreSchema.Collection.BUSINESS_DATE) ?: CollectionService.todayKey(),
                    doc.getString(FirestoreSchema.Collection.AGENT_NAME),
                    doc.getString(FirestoreSchema.Collection.AGENT_ID),
                    receiptNoOverride = doc.getString(FirestoreSchema.Collection.RECEIPT_NO),
                    paidAtOverride = doc.getTimestamp(FirestoreSchema.Collection.TIMESTAMP)?.toDate()?.time
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not apply collection ${doc.id}; it stays unsynced and will be retried", e)
                return
            }
            firestore.collection(FirestoreSchema.COLLECTIONS).document(doc.id)
                .update(FirestoreSchema.Collection.SYNCED_TO_ADMIN, true)
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync collection ${doc.id}", e)
        }
    }
}
