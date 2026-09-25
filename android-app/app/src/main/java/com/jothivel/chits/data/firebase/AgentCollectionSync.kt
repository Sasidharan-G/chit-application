package com.jothivel.chits.data.firebase

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AgentCollectionDoc(
    val requestId: String,
    val agentId: String,
    val agentName: String,
    val memberId: String,
    val memberName: String,
    val groupId: String,
    val chitNo: String,
    val amountPaise: Long,
    val mode: String,
    val referenceNo: String?,
    val receiptNo: String,
    val notes: String,
    val businessDate: String,
    val status: String,
    // true when the caller (the admin's own Collect screen) already applied this to its own
    // Room DB directly - FirebaseSyncService should not re-apply it. Agent collections leave
    // this false so the admin's listener picks them up and mirrors them in.
    val syncedToAdmin: Boolean = false,
    // UIDs allowed to read this document. Filled in from the chit's own `agentIds` when the document
    // is written (the security rules require it to match the chit's list exactly).
    val agentIds: List<String> = emptyList()
)

/**
 * Pushes a collection to Firestore `collections/{requestId}` (the admin's [FirebaseSyncService] listens
 * for `syncedToAdmin == false` docs and mirrors them into Room). requestId doubles as the document id so
 * a retried write can never create a duplicate. The security rules make a collection create-only: once it
 * exists nobody can change or delete it, so a retry that finds it already there counts as success.
 *
 * Offline-first: a failed write is queued in SharedPreferences and retried by [flushPending]
 * the next time the agent app has connectivity (called from the agent screens on start/refresh).
 */
object AgentCollectionSync {
    private const val PREFS_NAME = "jvc_pending_collections"
    private const val KEY_QUEUE = "queue"

    // queue() and flushPending() both do a read-modify-write on the same SharedPreferences-backed
    // queue. Without serialization, a queue() call landing while flushPending() is mid-flight
    // (its network awaits can span multiple documents) would read the queue before
    // flushPending's final writeQueue() runs, and flushPending's write of "remaining" would then
    // clobber the newly-queued entry, losing it. A coroutine Mutex (not a blocking `synchronized`,
    // since both functions are suspend and flushPending suspends across network calls) makes the
    // two mutually exclusive.
    private val queueMutex = Mutex()

    suspend fun push(context: Context, doc: AgentCollectionDoc): Boolean = withContext(Dispatchers.IO) {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
        if (firestore == null) {
            queueMutex.withLock { queueLocked(context, doc) }
            return@withContext false
        }
        if (writeOne(firestore, doc)) {
            true
        } else {
            queueMutex.withLock { queueLocked(context, doc) }
            false
        }
    }

    suspend fun flushPending(context: Context): Int = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pending = readQueue(prefs)
            if (pending.isEmpty()) return@withLock 0
            val firestore = FirebaseSetup.firestoreIfSignedIn(context) ?: return@withLock 0
            var flushed = 0
            val remaining = mutableListOf<AgentCollectionDoc>()
            pending.forEach { doc ->
                if (writeOne(firestore, doc)) flushed++ else remaining += doc
            }
            writeQueue(prefs, remaining)
            flushed
        }
    }

    /** True when the document is (now) safely in the cloud. */
    private suspend fun writeOne(firestore: FirebaseFirestore, doc: AgentCollectionDoc): Boolean {
        val ref = firestore.collection(FirestoreSchema.COLLECTIONS).document(doc.requestId)
        return try {
            val agentIds = doc.agentIds.ifEmpty { chitAgentIds(firestore, doc.groupId) }
            // An agent's payment must name the agents allowed to see it; the admin's own (already applied) ones may have none.
            if (agentIds.isEmpty() && !doc.syncedToAdmin) return false // chit not visible yet - keep it queued
            ref.set(toMap(doc.copy(agentIds = agentIds))).await()
            true
        } catch (e: Exception) {
            // A retry of a write that actually succeeded earlier is refused (documents are immutable),
            // which is the good case: it is already there.
            runCatching { ref.get().await().exists() }.getOrDefault(false)
        }
    }

    private suspend fun chitAgentIds(firestore: FirebaseFirestore, groupId: String): List<String> {
        val group = firestore.collection(FirestoreSchema.CHIT_GROUPS).document(groupId).get().await()
        @Suppress("UNCHECKED_CAST")
        return (group.get(FirestoreSchema.AGENT_IDS) as? List<String>).orEmpty()
    }

    fun pendingCount(context: Context): Int =
        readQueue(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)).size

    /** Caller must hold [queueMutex]. */
    internal fun queueLocked(context: Context, doc: AgentCollectionDoc) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readQueue(prefs).filterNot { it.requestId == doc.requestId }
        writeQueue(prefs, current + doc)
    }

    private fun toMap(doc: AgentCollectionDoc): Map<String, Any?> = mapOf(
        FirestoreSchema.Collection.AGENT_ID to doc.agentId,
        FirestoreSchema.Collection.AGENT_NAME to doc.agentName,
        FirestoreSchema.Collection.MEMBER_ID to doc.memberId,
        FirestoreSchema.Collection.MEMBER_NAME to doc.memberName,
        FirestoreSchema.Collection.GROUP_ID to doc.groupId,
        FirestoreSchema.Collection.CHIT_NO to doc.chitNo,
        FirestoreSchema.Collection.AMOUNT_PAISE to doc.amountPaise,
        FirestoreSchema.Collection.MODE to doc.mode,
        FirestoreSchema.Collection.REFERENCE_NO to doc.referenceNo,
        FirestoreSchema.Collection.RECEIPT_NO to doc.receiptNo,
        FirestoreSchema.Collection.NOTES to doc.notes,
        FirestoreSchema.Collection.BUSINESS_DATE to doc.businessDate,
        FirestoreSchema.Collection.TIMESTAMP to FieldValue.serverTimestamp(),
        FirestoreSchema.Collection.STATUS to doc.status,
        FirestoreSchema.Collection.SYNCED_TO_ADMIN to doc.syncedToAdmin,
        FirestoreSchema.Collection.REQUEST_ID to doc.requestId,
        FirestoreSchema.Collection.AGENT_IDS to doc.agentIds
    )

    internal fun readQueue(prefs: android.content.SharedPreferences): List<AgentCollectionDoc> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val ids = o.optJSONArray("agentIds")
                AgentCollectionDoc(
                    requestId = o.getString("requestId"),
                    agentId = o.getString("agentId"),
                    agentName = o.getString("agentName"),
                    memberId = o.getString("memberId"),
                    memberName = o.getString("memberName"),
                    groupId = o.getString("groupId"),
                    chitNo = o.getString("chitNo"),
                    amountPaise = o.getLong("amountPaise"),
                    mode = o.getString("mode"),
                    referenceNo = o.optString("referenceNo").ifBlank { null },
                    receiptNo = o.getString("receiptNo"),
                    notes = o.optString("notes"),
                    businessDate = o.getString("businessDate"),
                    status = o.getString("status"),
                    syncedToAdmin = o.optBoolean("syncedToAdmin", false),
                    agentIds = if (ids == null) emptyList() else (0 until ids.length()).map { ids.getString(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun writeQueue(prefs: android.content.SharedPreferences, items: List<AgentCollectionDoc>) {
        val array = JSONArray()
        items.forEach { doc ->
            array.put(JSONObject().apply {
                put("requestId", doc.requestId)
                put("agentId", doc.agentId)
                put("agentName", doc.agentName)
                put("memberId", doc.memberId)
                put("memberName", doc.memberName)
                put("groupId", doc.groupId)
                put("chitNo", doc.chitNo)
                put("amountPaise", doc.amountPaise)
                put("mode", doc.mode)
                put("referenceNo", doc.referenceNo ?: "")
                put("receiptNo", doc.receiptNo)
                put("notes", doc.notes)
                put("businessDate", doc.businessDate)
                put("status", doc.status)
                put("syncedToAdmin", doc.syncedToAdmin)
                put("agentIds", JSONArray(doc.agentIds))
            })
        }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }
}
