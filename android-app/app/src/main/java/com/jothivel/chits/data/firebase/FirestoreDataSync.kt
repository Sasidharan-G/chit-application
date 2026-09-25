package com.jothivel.chits.data.firebase

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.CollectionService
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Admin-only push of the reference data agents need to work offline: chit groups, their members,
 * memberships and installment schedules. Two ways to run it:
 *  - manually ("Sync Data to Cloud"): writes everything, which also repairs a cloud that lost data;
 *  - automatically ([AutoCloudSync]): writes only the documents that changed since the last push (see
 *    [CloudPushLedger]) and only once enough changes have piled up, to stay inside the free quota.
 * This is reference data, not a live feed, so a snapshot listener isn't needed here.
 */
object FirestoreDataSync {

    /** How many documents of each kind were written. */
    data class SyncResult(val groups: Int, val members: Int, val memberships: Int, val installments: Int)

    /** One document to write, and the group / customer it belongs to (used to count "changes"). */
    internal data class PushDoc(val collection: String, val id: String, val data: Map<String, Any?>, val subject: String) {
        val key: String get() = CloudPushLedger.key(collection, id)
        val hash: String get() = CloudPushLedger.hash(data)
    }

    private val syncMutex = Mutex()

    /** Every document that belongs in the cloud, stamped with the agents allowed to read it (see firestore.rules). */
    internal fun buildDocs(db: AppDatabase, access: Map<String, List<String>>): List<PushDoc> {
        // Push everything - including closed chits, deactivated members and memberships that
        // have left a chit - so those changes reach the agents' phones and a restore. Sending
        // only active rows meant a removal never overwrote the stale "active" copy in the cloud.
        // (Agent screens only offer ACTIVE chits and active members, so closed data is inert there.)
        val groups = db.groupDao().getAllGroupsSync()
        val groupIds = groups.mapTo(hashSetOf()) { it.id }
        val members = db.memberDao().getAllMembersSync()
        val memberships = groupIds.flatMap { db.membershipDao().getAllForGroupSync(it) }
        val installments = groupIds.flatMap { db.installmentDao().getInstallmentsForGroupSync(it) }

        val agentsByMember = HashMap<String, MutableSet<String>>()
        memberships.forEach { agentsByMember.getOrPut(it.memberId) { linkedSetOf() }.addAll(access[it.groupId].orEmpty()) }

        return groups.map { PushDoc(FirestoreSchema.CHIT_GROUPS, it.id, groupMap(it, access[it.id].orEmpty()), "G:${it.id}") } +
            members.map { PushDoc(FirestoreSchema.MEMBERS, it.id, memberMap(it, agentsByMember[it.id].orEmpty()), "M:${it.id}") } +
            memberships.map { PushDoc(FirestoreSchema.CHIT_MEMBERSHIPS, it.id, membershipMap(it, access[it.groupId].orEmpty()), "M:${it.memberId}") } +
            installments.map { PushDoc(FirestoreSchema.INSTALLMENTS, it.id, installmentMap(it, access[it.groupId].orEmpty()), "G:${it.groupId}") }
    }

    /** The groups and customers with at least one document that is not in the cloud yet or has changed. */
    internal fun dirtySubjects(docs: List<PushDoc>, pushedHash: (String) -> String?): Set<String> =
        docs.filter { pushedHash(it.key) != it.hash }.mapTo(linkedSetOf()) { it.subject }

    /** How many groups / customers are waiting to be sent (the "changes" that [AutoCloudSync] counts). */
    fun pendingChanges(context: Context): Int {
        val ledger = CloudPushLedger.snapshot(context)
        return dirtySubjects(buildDocs(AppDatabase.getDatabase(context), emptyMap())) { ledger[it] }.size
    }

    suspend fun syncAllToCloud(context: Context, onlyChanged: Boolean = false): Result<SyncResult> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account, sign in with the admin email, and check the internet connection."))
        return try {
            syncMutex.withLock {
                val db = AppDatabase.getDatabase(context)
                val docs = buildDocs(db, loadAgentAccess(firestore))
                val ledger = CloudPushLedger.snapshot(context)
                val toPush = if (onlyChanged) docs.filter { ledger[it.key] != it.hash } else docs
                toPush.chunked(400).forEach { chunk ->
                    val batch: WriteBatch = firestore.batch()
                    chunk.forEach { batch.set(firestore.collection(it.collection).document(it.id), it.data) }
                    batch.commit().await()
                    // Remember each batch as soon as it is safely in the cloud, so a failure half way
                    // does not send the finished part again.
                    CloudPushLedger.putAll(context, chunk.associate { it.key to it.hash })
                }
                Result.success(SyncResult(
                    groups = toPush.count { it.collection == FirestoreSchema.CHIT_GROUPS },
                    members = toPush.count { it.collection == FirestoreSchema.MEMBERS },
                    memberships = toPush.count { it.collection == FirestoreSchema.CHIT_MEMBERSHIPS },
                    installments = toPush.count { it.collection == FirestoreSchema.INSTALLMENTS }
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    data class RestoreResult(val groups: Int, val members: Int, val memberships: Int, val installments: Int, val collections: Int)

    /**
     * Admin-only pull of everything back down from Firestore into local Room - the counterpart
     * to [syncAllToCloud]. Meant for the "I uninstalled/lost the app, reinstalled, need my data
     * back" scenario: as long a "Sync Data to Cloud" push happened at some point before that,
     * this rebuilds the local database from what's in the cloud. Every table is inserted with
     * IGNORE-on-conflict, so rows that already exist on this device are never overwritten by an
     * older cloud snapshot - it only fills in what is missing.
     */
    suspend fun restoreAllFromCloud(context: Context): Result<RestoreResult> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected. Open Settings > Cloud account, sign in with the admin email, and check the internet connection."))
        return try {
            val db = AppDatabase.getDatabase(context)

            val groups = firestore.collection(FirestoreSchema.CHIT_GROUPS).get().await().documents.map { doc ->
                ChitGroupEntity().apply {
                    id = doc.id
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
            db.groupDao().insertAllIgnore(groups)

            val members = firestore.collection(FirestoreSchema.MEMBERS).get().await().documents.map { doc ->
                // Fall back to the existing local row for any field the cloud document doesn't
                // carry (e.g. an older snapshot predating a schema addition, or a partial write) -
                // insertAll below REPLACEs the whole row, so a missing field here would otherwise
                // null it out even though it's already correctly stored locally.
                val existing = db.memberDao().getMemberByIdSync(doc.id)
                MemberEntity().apply {
                    id = doc.id
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
                    aadhaarNoEncrypted = doc.getString("aadhaarNoEncrypted") ?: existing?.aadhaarNoEncrypted
                    panNo = doc.getString("panNo") ?: existing?.panNo
                    aadhaarDocumentPath = doc.getString("aadhaarDocumentPath") ?: existing?.aadhaarDocumentPath
                    panDocumentPath = doc.getString("panDocumentPath") ?: existing?.panDocumentPath
                    selectedChitId = doc.getString("selectedChitId") ?: existing?.selectedChitId
                    ticketNo = doc.getString("ticketNo") ?: existing?.ticketNo
                    installmentAmount = doc.getString("installmentAmount") ?: existing?.installmentAmount
                    joiningDate = doc.getString("joiningDate") ?: existing?.joiningDate
                    dueDate = doc.getString("dueDate") ?: existing?.dueDate
                    nomineeRelationship = doc.getString("nomineeRelationship") ?: existing?.nomineeRelationship
                }
            }
            db.memberDao().insertAllIgnore(members)

            val memberships = firestore.collection(FirestoreSchema.CHIT_MEMBERSHIPS).get().await().documents.mapNotNull { doc ->
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
            db.membershipDao().insertAll(memberships)

            val installments = firestore.collection(FirestoreSchema.INSTALLMENTS).get().await().documents.map { doc ->
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
            db.installmentDao().insertAllIgnore(installments)

            // Restore actual payment/receipt history too - CollectionService.record() is
            // idempotent on requestId, so re-running restore (or restoring on top of a device
            // that already has some of these) never creates duplicate payment rows.
            var restoredCollections = 0
            firestore.collection(FirestoreSchema.COLLECTIONS).get().await().documents.forEach { doc ->
                val requestId = doc.getString(FirestoreSchema.Collection.REQUEST_ID) ?: doc.id
                val memberId = doc.getString(FirestoreSchema.Collection.MEMBER_ID) ?: return@forEach
                val groupId = doc.getString(FirestoreSchema.Collection.GROUP_ID) ?: return@forEach
                val amountPaise = doc.getLong(FirestoreSchema.Collection.AMOUNT_PAISE) ?: return@forEach
                if (amountPaise <= 0) return@forEach
                val memberName = doc.getString(FirestoreSchema.Collection.MEMBER_NAME).orEmpty()
                val mode = doc.getString(FirestoreSchema.Collection.MODE) ?: "Cash"
                val referenceNo = doc.getString(FirestoreSchema.Collection.REFERENCE_NO)
                val notes = doc.getString(FirestoreSchema.Collection.NOTES).orEmpty()
                val businessDate = doc.getString(FirestoreSchema.Collection.BUSINESS_DATE) ?: CollectionService.todayKey()
                val agentName = doc.getString(FirestoreSchema.Collection.AGENT_NAME)?.ifBlank { null }
                val agentId = doc.getString(FirestoreSchema.Collection.AGENT_ID)?.ifBlank { null }
                runCatching {
                    CollectionService.record(
                        db, requestId, memberId, memberName, groupId, amountPaise, mode, referenceNo, notes, businessDate, agentName, agentId,
                        receiptNoOverride = doc.getString(FirestoreSchema.Collection.RECEIPT_NO),
                        paidAtOverride = doc.getTimestamp(FirestoreSchema.Collection.TIMESTAMP)?.toDate()?.time,
                        logActivity = false
                    )
                    restoredCollections++
                }
            }

            Result.success(RestoreResult(groups.size, members.size, memberships.size, installments.size, restoredCollections))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** groupId -> UIDs of the ACTIVE agents assigned to it (only the admin can list `agents`). */
    private suspend fun loadAgentAccess(firestore: FirebaseFirestore): Map<String, List<String>> {
        val access = HashMap<String, MutableList<String>>()
        firestore.collection(FirestoreSchema.AGENTS).get().await().documents
            .filter { it.getBoolean(FirestoreSchema.Agent.IS_ACTIVE) == true }
            .forEach { agent ->
                @Suppress("UNCHECKED_CAST")
                (agent.get(FirestoreSchema.Agent.ASSIGNED_GROUPS) as? List<String>).orEmpty().forEach { groupId ->
                    access.getOrPut(groupId) { mutableListOf() }.add(agent.id)
                }
            }
        return access.mapValues { it.value.sorted() }
    }

    /**
     * Re-stamps `agentIds` on everything already in the cloud after assignments, PINs or active flags
     * change, so a newly assigned agent can see the chit (and its earlier collections) and a removed or
     * deactivated one immediately loses access. Only documents whose list actually changes are written.
     */
    suspend fun refreshAgentAccess(context: Context): Result<Unit> {
        val firestore = FirebaseSetup.firestoreIfSignedIn(context)
            ?: return Result.failure(IllegalStateException("Cloud account is not connected."))
        return try {
            val access = loadAgentAccess(firestore)
            val memberships = firestore.collection(FirestoreSchema.CHIT_MEMBERSHIPS).get().await().documents
            val agentsByMember = HashMap<String, MutableSet<String>>()
            memberships.forEach { m ->
                val memberId = m.getString("memberId") ?: return@forEach
                val groupId = m.getString("groupId") ?: return@forEach
                agentsByMember.getOrPut(memberId) { linkedSetOf() }.addAll(access[groupId].orEmpty())
            }
            restamp(firestore, FirestoreSchema.CHIT_GROUPS, firestore.collection(FirestoreSchema.CHIT_GROUPS).get().await().documents) { access[it.id].orEmpty() }
            restamp(firestore, FirestoreSchema.CHIT_MEMBERSHIPS, memberships) { access[it.getString("groupId")].orEmpty() }
            restamp(firestore, FirestoreSchema.INSTALLMENTS, firestore.collection(FirestoreSchema.INSTALLMENTS).get().await().documents) { access[it.getString("groupId")].orEmpty() }
            restamp(firestore, FirestoreSchema.MEMBERS, firestore.collection(FirestoreSchema.MEMBERS).get().await().documents) { agentsByMember[it.id]?.sorted().orEmpty() }
            restamp(firestore, FirestoreSchema.COLLECTIONS, firestore.collection(FirestoreSchema.COLLECTIONS).get().await().documents) { access[it.getString("groupId")].orEmpty() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun restamp(
        firestore: FirebaseFirestore,
        collection: String,
        docs: List<com.google.firebase.firestore.DocumentSnapshot>,
        wanted: (com.google.firebase.firestore.DocumentSnapshot) -> List<String>
    ) {
        val changed = docs.filter { doc ->
            @Suppress("UNCHECKED_CAST")
            val current = (doc.get(FirestoreSchema.AGENT_IDS) as? List<String>).orEmpty().sorted()
            current != wanted(doc).sorted()
        }
        changed.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { doc -> batch.update(firestore.collection(collection).document(doc.id), FirestoreSchema.AGENT_IDS, wanted(doc).sorted()) }
            batch.commit().await()
        }
    }

    private fun groupMap(group: ChitGroupEntity, agentIds: Collection<String>): Map<String, Any?> = mapOf(
        "name" to group.name,
        "registerNo" to group.registerNo,
        "chitValue" to group.chitValue,
        "durationMonths" to group.durationMonths,
        "subscriberCount" to group.subscriberCount,
        "branch" to group.branch,
        "startDate" to group.startDate,
        "status" to group.status,
        FirestoreSchema.AGENT_IDS to agentIds.toList()
    )

    /** Aadhaar reference and document paths are deliberately NOT sent to the cloud (agents can read members). */
    private fun memberMap(member: MemberEntity, agentIds: Collection<String>): Map<String, Any?> = mapOf(
        "name" to member.name,
        "phone" to member.phone,
        "photoUrl" to member.photoUrl,
        "nomineeName" to member.nomineeName,
        "nomineePhone" to member.nomineePhone,
        "role" to member.role,
        "isActive" to member.isActive,
        "dob" to member.dob,
        "gender" to member.gender,
        "addressLine" to member.addressLine,
        "city" to member.city,
        "state" to member.state,
        "pincode" to member.pincode,
        "panNo" to member.panNo,
        "selectedChitId" to member.selectedChitId,
        "ticketNo" to member.ticketNo,
        "installmentAmount" to member.installmentAmount,
        "joiningDate" to member.joiningDate,
        "dueDate" to member.dueDate,
        "nomineeRelationship" to member.nomineeRelationship,
        FirestoreSchema.AGENT_IDS to agentIds.toList()
    )

    private fun membershipMap(membership: ChitMembershipEntity, agentIds: Collection<String>): Map<String, Any?> = mapOf(
        "memberId" to membership.memberId,
        "groupId" to membership.groupId,
        "ticketNo" to membership.ticketNo,
        "installmentAmountPaise" to membership.installmentAmountPaise,
        "joiningDate" to membership.joiningDate,
        "dueDate" to membership.dueDate,
        "isActive" to membership.isActive,
        FirestoreSchema.AGENT_IDS to agentIds.toList()
    )

    private fun installmentMap(installment: InstallmentEntity, agentIds: Collection<String>): Map<String, Any?> = mapOf(
        "groupId" to installment.groupId,
        "installmentNo" to installment.installmentNo,
        "baseAmount" to installment.baseAmount,
        "kasaruAmount" to installment.kasaruAmount,
        "payoutAmount" to installment.payoutAmount,
        "auctionDate" to installment.auctionDate,
        "status" to installment.status,
        "winningMemberId" to installment.winningMemberId,
        FirestoreSchema.AGENT_IDS to agentIds.toList()
    )
}
