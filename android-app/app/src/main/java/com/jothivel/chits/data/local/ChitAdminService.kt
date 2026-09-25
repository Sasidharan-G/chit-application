package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.models.ChitScheduleRow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Chit lifecycle operations that used to have no home at all: editing a chit, deactivating a
 * member, closing a finished chit and recording who won each month's auction. Every operation
 * validates first, runs in a single transaction and leaves an audit-log entry, and throws
 * [IllegalArgumentException] / [IllegalStateException] with a message that is safe to show.
 */
object ChitAdminService {
    private const val DATE_PATTERN = "dd-MMM-yyyy"

    private fun parseDate(value: String?) = runCatching {
        SimpleDateFormat(DATE_PATTERN, Locale.ENGLISH).apply { isLenient = false }.parse(value.orEmpty().trim())
    }.getOrNull()

    /**
     * Builds a new chit's installment table. Fixed-schedule plans use the printed monthly rows
     * (baseAmount is stored GROSS = printed amount + kasaru, because the due is computed as
     * baseAmount - kasaruAmount). Flat plans split the chit value evenly in paise and roll the
     * leftover remainder into the LAST installment, so the schedule always sums to exactly the
     * chit value (a plain `value / months` silently dropped up to months-1 paise per member).
     */
    fun buildInstallments(groupId: String, chitValuePaise: Int, months: Int, fixedSchedule: List<ChitScheduleRow>?): List<InstallmentEntity> {
        require(months > 0) { "Months must be positive" }
        val flat = chitValuePaise / months
        val remainder = chitValuePaise - flat * months
        return (1..months).map { number ->
            val row = fixedSchedule?.getOrNull(number - 1)
            InstallmentEntity().apply {
                id = "$groupId-I$number"
                this.groupId = groupId
                installmentNo = number
                baseAmount = row?.let { (it.baseAmount + it.kasaruAmount) * 100 } ?: (if (number == months) flat + remainder else flat)
                kasaruAmount = row?.let { it.kasaruAmount * 100 } ?: 0
                payoutAmount = row?.takeIf { it.payoutAmount > 0 }?.let { it.payoutAmount * 100 }
                auctionDate = null
                status = "UPCOMING"
                winningMemberId = null
            }
        }
    }

    // ── Editing a chit ───────────────────────────────────────────────────────────────────

    /**
     * Edits the descriptive fields of a chit. Value and duration are deliberately not editable -
     * they define the installment schedule that dues and payments are already recorded against.
     * The start date drives every due date, so it can only change while no payment exists.
     */
    fun updateGroupDetails(db: AppDatabase, groupId: String, name: String, registerNo: String, branch: String, startDate: String, subscriberCount: Int) {
        require(name.isNotBlank()) { "Chit name is required" }
        require(registerNo.isNotBlank()) { "Chit number is required" }
        require(branch.isNotBlank()) { "Branch is required" }
        require(parseDate(startDate) != null) { "Start date must look like 01-Jan-2026" }
        require(subscriberCount in 1..100) { "Members must be between 1 and 100" }
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Chit was not found")
            val clash = db.groupDao().getGroupByRegisterNoSync(registerNo.trim())
            require(clash == null || clash.id == groupId) { "Chit number ${registerNo.trim()} is already used by another chit" }
            val active = db.membershipDao().countActiveForGroupSync(groupId)
            require(subscriberCount >= active) { "This chit already has $active active members; it cannot have fewer than that" }
            val startChanged = group.startDate?.trim() != startDate.trim()
            if (startChanged) {
                check(db.paymentDao().countForGroupSync(groupId) == 0) { "The start date cannot change once payments have been recorded for this chit" }
            }
            db.groupDao().updateDetails(groupId, name.trim(), registerNo.trim(), branch.trim(), startDate.trim(), subscriberCount)
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "GROUP_EDITED", title = "Chit edited", description = "${registerNo.trim()} - ${name.trim()}"))
        }
    }

    // ── Closing a finished chit ──────────────────────────────────────────────────────────

    /** Active members of [groupId] that still owe something as of now. */
    fun membersWithPendingDues(db: AppDatabase, groupId: String): Int =
        db.membershipDao().getActiveForGroupSync(groupId).count { CollectionService.calculateDuePaise(db, it.memberId, groupId) > 0 }

    /**
     * Marks a chit COMPLETED. It then drops out of collection / add-member lists (those only
     * offer ACTIVE chits) but every record stays. Open dues block the close unless [force].
     */
    fun closeGroup(db: AppDatabase, groupId: String, force: Boolean = false): Int {
        var pending = 0
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Chit was not found")
            check(group.status.isNullOrBlank() || group.status == "ACTIVE") { "Only an active chit can be closed" }
            pending = membersWithPendingDues(db, groupId)
            check(force || pending == 0) { "$pending member(s) still have pending dues. Collect them first, or close anyway." }
            db.groupDao().updateStatus(groupId, "COMPLETED")
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "GROUP_CLOSED", title = "Chit closed", description = "${group.registerNo} - ${group.name}" + if (pending > 0) " ($pending with dues)" else ""))
        }
        return pending
    }

    fun reopenGroup(db: AppDatabase, groupId: String) {
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Chit was not found")
            check(group.status == "COMPLETED") { "Only a closed chit can be reopened" }
            db.groupDao().updateStatus(groupId, "ACTIVE")
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "GROUP_REOPENED", title = "Chit reopened", description = "${group.registerNo} - ${group.name}"))
        }
    }

    // ── Members leaving / rejoining a chit ───────────────────────────────────────────────

    /** Amount (paise) a member still owes in a chit - shown before confirming an exit. */
    fun pendingDuePaise(db: AppDatabase, memberId: String, groupId: String): Long = CollectionService.calculateDuePaise(db, memberId, groupId)

    /**
     * Takes a member out of a chit: they stop appearing in collection and dues lists and free
     * their seat. Their payment history is untouched, and [reactivateMember] undoes it.
     */
    fun deactivateMember(db: AppDatabase, memberId: String, groupId: String) {
        db.runInTransaction {
            val membership = db.membershipDao().getSync(memberId, groupId) ?: throw IllegalStateException("This customer is not in the chit")
            check(membership.isActive) { "This customer has already left the chit" }
            check(db.installmentDao().getWonByMemberSync(groupId, memberId).isEmpty()) { "This customer has won an auction in this chit, so they cannot be removed" }
            db.membershipDao().setActive(memberId, groupId, false)
            val name = db.memberDao().getMemberByIdSync(memberId)?.name ?: memberId
            val group = db.groupDao().getGroupByIdSync(groupId)
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "MEMBER_LEFT_CHIT", title = "Member removed from chit", description = "$name left ${group?.registerNo ?: groupId}"))
        }
    }

    fun reactivateMember(db: AppDatabase, memberId: String, groupId: String) {
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Chit was not found")
            val membership = db.membershipDao().getSync(memberId, groupId) ?: throw IllegalStateException("This customer was never in the chit")
            check(!membership.isActive) { "This customer is already active in the chit" }
            check(db.membershipDao().countActiveForGroupSync(groupId) < group.subscriberCount) { "The chit is full (${group.subscriberCount} members)" }
            db.membershipDao().setActive(memberId, groupId, true)
            val name = db.memberDao().getMemberByIdSync(memberId)?.name ?: memberId
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "MEMBER_REJOINED_CHIT", title = "Member rejoined chit", description = "$name rejoined ${group.registerNo}"))
        }
    }

    // ── Auction winners ──────────────────────────────────────────────────────────────────

    /**
     * Records who won an installment's auction and the prize (payout) amount. Each installment
     * has exactly one winner and a customer can win only once per chit. The winner is what a
     * Delivery entry is later checked against, so the money paid out can never exceed the prize.
     */
    fun setWinner(db: AppDatabase, groupId: String, installmentNo: Int, memberId: String, payoutPaise: Long, auctionDate: String) {
        require(payoutPaise in 1..Int.MAX_VALUE.toLong()) { "Enter the payout amount" }
        require(parseDate(auctionDate) != null) { "Auction date must look like 01-Jan-2026" }
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Chit was not found")
            check(group.status.isNullOrBlank() || group.status == "ACTIVE") { "Winners can only be recorded on an active chit" }
            val installment = db.installmentDao().getByNumberSync(groupId, installmentNo) ?: throw IllegalStateException("Installment $installmentNo was not found")
            check(installment.winningMemberId.isNullOrBlank()) { "Installment $installmentNo already has a winner" }
            check(db.membershipDao().getSync(memberId, groupId)?.isActive == true) { "This customer is not an active member of the chit" }
            check(db.installmentDao().getWonByMemberSync(groupId, memberId).isEmpty()) { "This customer has already won an auction in this chit" }
            check(db.installmentDao().setWinnerIfUnset(installment.id, memberId, payoutPaise.toInt(), auctionDate.trim()) == 1) { "Installment $installmentNo already has a winner" }
            val name = db.memberDao().getMemberByIdSync(memberId)?.name ?: memberId
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "AUCTION_WINNER_SET", title = "Auction winner recorded", description = "${group.registerNo} #$installmentNo - $name - ₹${payoutPaise / 100}"))
        }
    }

    /** Undoes a winner entered by mistake - refused once any prize money has been delivered. */
    fun clearWinner(db: AppDatabase, groupId: String, installmentNo: Int, reason: String) {
        require(reason.trim().length >= 4) { "Enter a reason" }
        db.runInTransaction {
            val installment = db.installmentDao().getByNumberSync(groupId, installmentNo) ?: throw IllegalStateException("Installment $installmentNo was not found")
            val winner = installment.winningMemberId?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Installment $installmentNo has no winner")
            check(db.financialTransactionDao().getPostedTotalForMemberGroupTypeSync(winner, groupId, "DELIVERY") == 0L) { "Prize money has already been delivered; reverse those Delivery entries first" }
            db.installmentDao().clearWinner(installment.id)
            val group = db.groupDao().getGroupByIdSync(groupId)
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "AUCTION_WINNER_CLEARED", title = "Auction winner cleared", description = "${group?.registerNo ?: groupId} #$installmentNo - ${reason.trim()}"))
        }
    }

    /** Sum of the prizes recorded for [memberId] in [groupId], in paise. */
    fun prizePaise(db: AppDatabase, memberId: String, groupId: String): Long =
        db.installmentDao().getWonByMemberSync(groupId, memberId).sumOf { (it.payoutAmount ?: 0).toLong() }

    fun groupOrNull(db: AppDatabase, groupId: String): ChitGroupEntity? = db.groupDao().getGroupByIdSync(groupId)
}
