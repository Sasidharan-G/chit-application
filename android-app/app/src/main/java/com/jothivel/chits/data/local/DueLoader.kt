package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import java.util.Date

/** One customer's standing in one chit, with the full due breakdown. */
data class MemberGroupDue(
    val member: MemberEntity,
    val group: ChitGroupEntity,
    val membership: ChitMembershipEntity,
    val breakdown: DueBreakdown
)

/**
 * Loads dues for many customers with a fixed number of queries. The per-customer
 * [CollectionService.calculateDueBreakdown] issues four queries each, so listing 500 customers
 * meant ~2000 queries on the UI's critical path (Pending, Today's Work, chit detail). Here the
 * groups, installments, memberships and payments are read once and the pure calculation is run
 * over them - the numbers are identical, only the query count changes.
 */
object DueLoader {

    /** Every active membership whose customer and chit still exist. */
    fun loadAll(db: AppDatabase, asOf: Date = Date()): List<MemberGroupDue> {
        val members = db.memberDao().getAllMembersSync().associateBy { it.id }
        val groups = db.groupDao().getAllGroupsSync().associateBy { it.id }
        val installmentsByGroup = groups.keys.associateWith { db.installmentDao().getInstallmentsForGroupSync(it) }
        val paymentsByMemberGroup = db.paymentDao().getAllPaymentsSync().groupBy { it.memberId to it.groupId }
        return db.membershipDao().getAllActiveSync().mapNotNull { membership ->
            val member = members[membership.memberId] ?: return@mapNotNull null
            val group = groups[membership.groupId] ?: return@mapNotNull null
            MemberGroupDue(
                member, group, membership,
                CollectionService.calculateDueBreakdown(
                    group,
                    installmentsByGroup[group.id].orEmpty(),
                    membership,
                    paymentsByMemberGroup[membership.memberId to membership.groupId].orEmpty(),
                    asOf
                )
            )
        }
    }

    /** Due breakdown per active member of a single chit, keyed by member id. */
    fun loadForGroup(db: AppDatabase, groupId: String, asOf: Date = Date()): Map<String, DueBreakdown> {
        val group = db.groupDao().getGroupByIdSync(groupId) ?: return emptyMap()
        val installments = db.installmentDao().getInstallmentsForGroupSync(groupId)
        val paymentsByMember = db.paymentDao().getPaymentsForGroupSync(groupId).groupBy { it.memberId }
        return db.membershipDao().getActiveForGroupSync(groupId).associate { membership ->
            membership.memberId to CollectionService.calculateDueBreakdown(
                group, installments, membership, paymentsByMember[membership.memberId].orEmpty(), asOf
            )
        }
    }
}
