package com.jothivel.chits.data.firebase

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.CollectionService
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent-side counterpart to [FirestoreDataSync]: downloads the chit groups this agent is
 * assigned to (plus their members/memberships/installments) into the agent phone's own Room DB.
 * CollectionService.record() needs those rows locally to work — an agent phone otherwise has no
 * seed data of its own, since Room is per-device local storage.
 *
 * Security rules only let an agent read documents stamped with their own UID in `agentIds`, so every
 * list query below carries `array-contains(uid)` - that filter is what lets Firestore prove the query
 * is allowed. The assigned-chit filter is then applied locally.
 */
object AgentDataSync {

    suspend fun syncAssignedGroups(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val assignedGroupIds = prefs.getAgentAssignedGroups()
        if (assignedGroupIds.isEmpty()) return@withContext Result.success(0)
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return@withContext Result.failure(IllegalStateException("Not signed in or no internet connection."))
        val uid = prefs.getAgentId()
        val assigned = assignedGroupIds.toHashSet()
        try {
            val db = AppDatabase.getDatabase(context)

            // Groups: read by id. One the agent is no longer assigned to is refused - skip it.
            val groups = assignedGroupIds.mapNotNull { groupId ->
                val doc = runCatching { firestore.collection(FirestoreSchema.CHIT_GROUPS).document(groupId).get().await() }.getOrNull()
                if (doc == null || !doc.exists()) null else ChitGroupEntity().apply {
                    id = groupId
                    name = doc.getString("name")
                    registerNo = doc.getString("registerNo")
                    chitValue = (doc.getLong("chitValue") ?: 0L).toInt()
                    durationMonths = (doc.getLong("durationMonths") ?: 0L).toInt()
                    subscriberCount = (doc.getLong("subscriberCount") ?: 0L).toInt()
                    branch = doc.getString("branch")
                    startDate = doc.getString("startDate")
                    status = doc.getString("status") ?: "ACTIVE"
                }
            }
            db.groupDao().insertAll(groups)

            val memberships = visibleDocs(firestore, FirestoreSchema.CHIT_MEMBERSHIPS, uid)
                .filter { it.getString("groupId") in assigned }
                .mapNotNull { doc ->
                    val memberId = doc.getString("memberId") ?: return@mapNotNull null
                    val groupId = doc.getString("groupId") ?: return@mapNotNull null
                    ChitMembershipEntity().apply {
                        id = doc.id
                        this.memberId = memberId
                        this.groupId = groupId
                        ticketNo = doc.getString("ticketNo")
                        installmentAmountPaise = doc.getLong("installmentAmountPaise") ?: 0L
                        joiningDate = doc.getString("joiningDate")
                        dueDate = doc.getString("dueDate")
                        isActive = doc.getBoolean("isActive") ?: true
                    }
                }
            db.membershipDao().upsertAll(memberships)

            val memberIds = memberships.map { it.memberId }.distinct()
            val members = memberIds.mapNotNull { memberId ->
                val doc = runCatching { firestore.collection(FirestoreSchema.MEMBERS).document(memberId).get().await() }.getOrNull()
                if (doc == null || !doc.exists()) return@mapNotNull null
                // Fall back to the existing local row for any field the cloud document doesn't
                // carry - insertAll below REPLACEs the whole row, so a missing field here would
                // otherwise null it out even though it's already correctly stored locally.
                val existing = db.memberDao().getMemberByIdSync(memberId)
                MemberEntity().apply {
                    id = memberId
                    name = doc.getString("name") ?: existing?.name
                    phone = doc.getString("phone") ?: existing?.phone
                    photoUrl = doc.getString("photoUrl") ?: existing?.photoUrl
                    nomineeName = doc.getString("nomineeName") ?: existing?.nomineeName
                    nomineePhone = doc.getString("nomineePhone") ?: existing?.nomineePhone
                    role = doc.getString("role") ?: existing?.role
                    isActive = doc.getBoolean("isActive") ?: existing?.isActive ?: true
                    dob = doc.getString("dob") ?: existing?.dob
                    gender = doc.getString("gender") ?: existing?.gender
                    addressLine = doc.getString("addressLine") ?: existing?.addressLine
                    city = doc.getString("city") ?: existing?.city
                    state = doc.getString("state") ?: existing?.state
                    pincode = doc.getString("pincode") ?: existing?.pincode
                    aadhaarNoEncrypted = existing?.aadhaarNoEncrypted // never comes from the cloud
                    panNo = doc.getString("panNo") ?: existing?.panNo
                    aadhaarDocumentPath = existing?.aadhaarDocumentPath
                    panDocumentPath = existing?.panDocumentPath
                    selectedChitId = doc.getString("selectedChitId") ?: existing?.selectedChitId
                    ticketNo = doc.getString("ticketNo") ?: existing?.ticketNo
                    installmentAmount = doc.getString("installmentAmount") ?: existing?.installmentAmount
                    joiningDate = doc.getString("joiningDate") ?: existing?.joiningDate
                    dueDate = doc.getString("dueDate") ?: existing?.dueDate
                    nomineeRelationship = doc.getString("nomineeRelationship") ?: existing?.nomineeRelationship
                }
            }
            db.memberDao().insertAll(members)

            val installments = visibleDocs(firestore, FirestoreSchema.INSTALLMENTS, uid)
                .filter { it.getString("groupId") in assigned }
                .map { doc ->
                    InstallmentEntity().apply {
                        id = doc.id
                        groupId = doc.getString("groupId")
                        installmentNo = (doc.getLong("installmentNo") ?: 0L).toInt()
                        baseAmount = (doc.getLong("baseAmount") ?: 0L).toInt()
                        kasaruAmount = doc.getLong("kasaruAmount")?.toInt()
                        payoutAmount = doc.getLong("payoutAmount")?.toInt()
                        auctionDate = doc.getString("auctionDate")
                        status = doc.getString("status") ?: "PENDING"
                        winningMemberId = doc.getString("winningMemberId")
                    }
                }
            db.installmentDao().insertAll(installments)

            // Pull in the collections the OTHER agents (and the admin) have recorded against these
            // chits. Room is per-device, so without this an agent's ledger only ever knew about
            // their own receipts and would happily show - and re-collect - a due that a colleague
            // had already taken. record() is idempotent on requestId, so the agent's own receipts
            // (already local) and earlier pulls are skipped, and the original receipt number and
            // time are kept.
            pullGroupCollections(db, visibleDocs(firestore, FirestoreSchema.COLLECTIONS, uid).filter { it.getString(FirestoreSchema.Collection.GROUP_ID) in assigned })

            Result.success(groups.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Every document in [collection] this agent may see (stamped with their UID). */
    private suspend fun visibleDocs(firestore: FirebaseFirestore, collection: String, uid: String): List<DocumentSnapshot> =
        firestore.collection(collection).whereArrayContains(FirestoreSchema.AGENT_IDS, uid).get().await().documents

    /** Returns how many collections were newly applied to the local ledger. */
    private fun pullGroupCollections(db: AppDatabase, docs: List<DocumentSnapshot>): Int {
        var applied = 0
        docs.forEach { doc ->
            val requestId = doc.getString(FirestoreSchema.Collection.REQUEST_ID) ?: doc.id
            if (db.collectionReceiptDao().getByRequestIdSync(requestId) != null) return@forEach
            val memberId = doc.getString(FirestoreSchema.Collection.MEMBER_ID) ?: return@forEach
            val groupId = doc.getString(FirestoreSchema.Collection.GROUP_ID) ?: return@forEach
            val amountPaise = doc.getLong(FirestoreSchema.Collection.AMOUNT_PAISE)?.takeIf { it > 0 } ?: return@forEach
            runCatching {
                CollectionService.record(
                    db = db,
                    requestId = requestId,
                    memberId = memberId,
                    memberName = doc.getString(FirestoreSchema.Collection.MEMBER_NAME).orEmpty(),
                    groupId = groupId,
                    amountPaise = amountPaise,
                    mode = doc.getString(FirestoreSchema.Collection.MODE) ?: "Cash",
                    referenceNo = doc.getString(FirestoreSchema.Collection.REFERENCE_NO),
                    notes = doc.getString(FirestoreSchema.Collection.NOTES).orEmpty(),
                    businessDate = doc.getString(FirestoreSchema.Collection.BUSINESS_DATE) ?: CollectionService.todayKey(),
                    collectedBy = doc.getString(FirestoreSchema.Collection.AGENT_NAME)?.ifBlank { null },
                    collectedByAgentId = doc.getString(FirestoreSchema.Collection.AGENT_ID)?.ifBlank { null },
                    receiptNoOverride = doc.getString(FirestoreSchema.Collection.RECEIPT_NO),
                    paidAtOverride = doc.getTimestamp(FirestoreSchema.Collection.TIMESTAMP)?.toDate()?.time,
                    logActivity = false
                )
                applied++
            }
        }
        return applied
    }
}
