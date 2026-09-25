package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.CollectionReceiptEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.PaymentEntity
import com.jothivel.chits.data.models.ChitTemplate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SavedCollection(
    val receiptNo: String,
    val amountPaise: Long,
    val mode: String,
    val businessDate: String,
    val referenceNo: String?
)

data class DueBreakdown(
    val payablePaise: Long,
    val paidPaise: Long,
    val pendingPaise: Long,
    val pendingInstallments: List<Int>,
    val earliestDueDate: String,
    val overdueDays: Int,
    val lastPaidAt: Long?
)

data class CalendarScheduledDue(
    val dateKey: String,
    val memberId: String,
    val memberName: String,
    val groupId: String,
    val chitNo: String,
    val installmentNo: Int,
    val remainingPaise: Long
)

object CollectionService {
    private val storageDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun todayKey(): String = synchronized(storageDate) { storageDate.format(Date()) }

    fun calculateDuePaise(db: AppDatabase, memberId: String, groupId: String, asOf: Date = Date()): Long {
        return calculateDueBreakdown(db, memberId, groupId, asOf).pendingPaise
    }

    /**
     * Like [calculateDuePaise], but once every calendar-due installment is fully settled, looks
     * ahead to the very next unpaid installment - regardless of whether its own calendar month
     * has arrived yet - so the collection screen can pre-fill an amount for a member paying
     * ahead of schedule. Deliberately NOT used for Pending/Ledger/dashboard totals: those must
     * stay calendar-gated so a member who simply hasn't pre-paid the next month isn't
     * misreported as overdue.
     */
    fun calculateCollectableDuePaise(db: AppDatabase, memberId: String, groupId: String, asOf: Date = Date()): Long {
        val group = db.groupDao().getGroupByIdSync(groupId) ?: return 0L
        val installments = db.installmentDao().getInstallmentsForGroupSync(groupId).sortedBy { it.installmentNo }
        if (installments.isEmpty()) return 0L
        val membership = db.membershipDao().getSync(memberId, groupId)
        val payments = db.paymentDao().getPaymentsByMemberSync(memberId).filter { it.groupId == groupId }
        val directPaid = payments.filterNot { it.installmentId.equals("ADVANCE", true) }
            .groupBy { it.installmentId }
            .mapValues { (_, rows) -> rows.sumOf { it.amountPaid } }
        var advanceCredit = payments.filter { it.installmentId.equals("ADVANCE", true) }.sumOf { it.amountPaid }
        // Walk every installment in order (not just the calendar-due ones), consuming direct
        // payments then advance credit exactly like buildCalendarSchedule does - so a member who
        // paid ahead in a lump sum has that credit correctly offset against the next installment
        // shown here, instead of this looking like the full amount is freshly due.
        for (installment in installments) {
            val scheduled = scheduledAmountPaise(group, membership?.installmentAmountPaise, installment.baseAmount, installment.kasaruAmount).coerceAtLeast(0L)
            var remaining = (scheduled - directPaid[installment.installmentNo.toString()].orZero()).coerceAtLeast(0L)
            val creditUsed = minOf(advanceCredit, remaining)
            advanceCredit -= creditUsed
            remaining -= creditUsed
            if (remaining > 0) return remaining
        }
        return 0L
    }

    /**
     * Builds the complete unpaid monthly schedule used by the dashboard calendar.
     * Direct installment payments and ADVANCE credit are consumed in installment order,
     * so a fully-covered member is never shown again as due for that month.
     */
    fun buildCalendarSchedule(db: AppDatabase): List<CalendarScheduledDue> {
        val members = db.memberDao().getAllMembersSync().associateBy { it.id }
        val groups = db.groupDao().getAllGroupsSync().associateBy { it.id }
        val paymentsByMembership = db.paymentDao().getAllPaymentsSync().groupBy { it.memberId to it.groupId }
        val installmentsByGroup = groups.keys.associateWith { db.installmentDao().getInstallmentsForGroupSync(it) }

        return db.membershipDao().getAllActiveSync().flatMap { membership ->
            val member = members[membership.memberId] ?: return@flatMap emptyList()
            val group = groups[membership.groupId] ?: return@flatMap emptyList()
            val startDate = parseDate(group.startDate) ?: return@flatMap emptyList()
            val payments = paymentsByMembership[membership.memberId to membership.groupId].orEmpty()
            val directPaid = payments
                .filterNot { it.installmentId.equals("ADVANCE", true) }
                .groupBy { it.installmentId }
                .mapValues { (_, rows) -> rows.sumOf { it.amountPaid } }
            var advanceCredit = payments.filter { it.installmentId.equals("ADVANCE", true) }.sumOf { it.amountPaid }

            installmentsByGroup[membership.groupId].orEmpty().mapNotNull { installment ->
                val scheduled = scheduledAmountPaise(group, membership.installmentAmountPaise, installment.baseAmount, installment.kasaruAmount).coerceAtLeast(0L)
                var remaining = (scheduled - directPaid[installment.installmentNo.toString()].orZero()).coerceAtLeast(0L)
                val creditUsed = minOf(advanceCredit, remaining)
                advanceCredit -= creditUsed
                remaining -= creditUsed
                if (remaining <= 0) return@mapNotNull null

                val dueDate = Calendar.getInstance().apply {
                    time = startDate
                    add(Calendar.MONTH, installment.installmentNo - 1)
                }.time
                CalendarScheduledDue(
                    dateKey = synchronized(storageDate) { storageDate.format(dueDate) },
                    memberId = member.id,
                    memberName = member.name,
                    groupId = group.id,
                    chitNo = group.registerNo,
                    installmentNo = installment.installmentNo,
                    remainingPaise = remaining
                )
            }
        }.sortedWith(compareBy<CalendarScheduledDue> { it.dateKey }.thenBy { it.memberName }.thenBy { it.chitNo })
    }

    fun calculateDueBreakdown(db: AppDatabase, memberId: String, groupId: String, asOf: Date = Date()): DueBreakdown {
        val group = db.groupDao().getGroupByIdSync(groupId)
            ?: return DueBreakdown(0, 0, 0, emptyList(), "-", 0, null)
        val installments = db.installmentDao().getInstallmentsForGroupSync(groupId)
        if (installments.isEmpty()) return DueBreakdown(0, 0, 0, emptyList(), "-", 0, null)
        val membership = db.membershipDao().getSync(memberId, groupId)
        val payments = db.paymentDao().getPaymentsByMemberSync(memberId).filter { it.groupId == groupId }
        return calculateDueBreakdown(group, installments, membership, payments, asOf)
    }

    /**
     * The same due/paid/pending calculation as the database-backed overload above, but over rows
     * the caller has already loaded. Screens that list many members (Pending, group detail) load
     * the groups, installments and payments once and call this per member instead of issuing
     * four queries per row. [payments] must already be limited to this member and group.
     */
    fun calculateDueBreakdown(
        group: ChitGroupEntity,
        installments: List<InstallmentEntity>,
        membership: ChitMembershipEntity?,
        payments: List<PaymentEntity>,
        asOf: Date = Date()
    ): DueBreakdown {
        if (installments.isEmpty()) return DueBreakdown(0, 0, 0, emptyList(), "-", 0, null)
        val dueCount = installmentsDue(group.startDate, asOf, group.durationMonths).coerceAtMost(installments.size)
        val dueInstallments = installments.take(dueCount)
        fun scheduledAmount(base: Int, kasaru: Int?) = scheduledAmountPaise(group, membership?.installmentAmountPaise, base, kasaru).coerceAtLeast(0L)
        val scheduledPayable = dueInstallments.sumOf { scheduledAmount(it.baseAmount, it.kasaruAmount) }
        val totalPaid = payments.sumOf { it.amountPaid }
        var credit = payments.filter { it.installmentId.equals("ADVANCE", true) }.sumOf { it.amountPaid }
        val pendingNumbers = mutableListOf<Int>()
        dueInstallments.forEach { installment ->
            val allocated = payments.filter { it.installmentId == installment.installmentNo.toString() }.sumOf { it.amountPaid }
            var shortfall = (scheduledAmount(installment.baseAmount, installment.kasaruAmount) - allocated).coerceAtLeast(0L)
            val usedCredit = minOf(credit, shortfall)
            credit -= usedCredit
            shortfall -= usedCredit
            if (shortfall > 0) pendingNumbers += installment.installmentNo
        }
        val firstPendingDate = pendingNumbers.firstOrNull()?.let { installmentNo ->
            parseDate(group.startDate)?.let { start -> Calendar.getInstance().apply {
                time = start
                add(Calendar.MONTH, installmentNo - 1)
            }.time }
        }
        val overdueDays = firstPendingDate?.let { due ->
            ((dayStart(asOf).time - dayStart(due).time) / 86_400_000L).coerceAtLeast(0L).toInt()
        } ?: 0
        return DueBreakdown(
            payablePaise = scheduledPayable,
            paidPaise = totalPaid,
            pendingPaise = (scheduledPayable - totalPaid).coerceAtLeast(0L),
            pendingInstallments = pendingNumbers,
            earliestDueDate = firstPendingDate?.let { SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH).format(it) } ?: "-",
            overdueDays = overdueDays,
            lastPaidAt = payments.maxOfOrNull { it.paidAt }
        )
    }

    fun record(
        db: AppDatabase,
        requestId: String,
        memberId: String,
        memberName: String,
        groupId: String,
        amountPaise: Long,
        mode: String,
        referenceNo: String?,
        notes: String,
        businessDate: String = todayKey(),
        collectedBy: String? = null,
        collectedByAgentId: String? = null,
        // Set when replaying a collection that already has an identity elsewhere (an agent's
        // receipt pulled from the cloud): keeps the receipt number the customer was handed and
        // the time it was actually taken, instead of minting new ones at replay time.
        receiptNoOverride: String? = null,
        paidAtOverride: Long? = null,
        logActivity: Boolean = true
    ): SavedCollection {
        require(amountPaise > 0) { "Enter a valid amount" }
        require(mode == "Cash" || !referenceNo.isNullOrBlank()) { "Reference / UTR is required" }

        db.collectionReceiptDao().getByRequestIdSync(requestId)?.let {
            return SavedCollection(it.receiptNo, it.amountPaidPaise, it.mode, it.businessDate, it.referenceNo)
        }

        val group = db.groupDao().getGroupByIdSync(groupId) ?: error("Selected chit was not found")
        require(db.membershipDao().getSync(memberId, groupId)?.isActive == true) { "Customer is not active in this chit" }
        val installments = db.installmentDao().getInstallmentsForGroupSync(groupId)
        require(installments.isNotEmpty()) { "No installment schedule found for this chit" }

        val receiptNo = receiptNoOverride?.takeIf { it.isNotBlank() }
            ?: "JVC-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${UUID.randomUUID().toString().take(4).uppercase()}"
        val paidAt = paidAtOverride ?: System.currentTimeMillis()
        var saved: SavedCollection? = null

        db.runInTransaction {
            val receipt = CollectionReceiptEntity().apply {
                id = UUID.randomUUID().toString()
                this.requestId = requestId
                this.receiptNo = receiptNo
                this.memberId = memberId
                this.groupId = groupId
                this.amountPaidPaise = amountPaise
                this.mode = mode
                this.referenceNo = referenceNo?.trim()?.ifBlank { null }
                this.notes = notes.trim()
                this.businessDate = businessDate
                this.paidAt = paidAt
                status = "SAVED"
            }
            db.collectionReceiptDao().insert(receipt)

            var remaining = amountPaise
            installments.forEach { installment ->
                if (remaining <= 0) return@forEach
                val membershipAmount = db.membershipDao().getSync(memberId, groupId)?.installmentAmountPaise
                val scheduled = scheduledAmountPaise(group, membershipAmount, installment.baseAmount, installment.kasaruAmount)
                val alreadyPaid = db.paymentDao()
                    .getPaymentsForInstallmentSync(memberId, groupId, installment.installmentNo.toString())
                    .sumOf { it.amountPaid }
                val pending = (scheduled - alreadyPaid).coerceAtLeast(0L)
                if (pending > 0) {
                    val allocation = minOf(remaining, pending)
                    db.paymentDao().insertPayment(paymentLine(memberId, groupId, installment.installmentNo.toString(), allocation, mode, referenceNo, receiptNo, paidAt, if (allocation == pending) "PAID" else "PARTIAL", collectedBy, collectedByAgentId))
                    remaining -= allocation
                }
            }
            if (remaining > 0) {
                db.paymentDao().insertPayment(paymentLine(memberId, groupId, "ADVANCE", remaining, mode, referenceNo, receiptNo, paidAt, "ADVANCE", collectedBy, collectedByAgentId))
            }
            if (logActivity) db.activityLogDao().insertLog(
                ActivityLogEntity(
                    actionType = "PAYMENT_RECORDED",
                    title = "Collection received",
                    description = "$memberName • ${group.registerNo} • ₹${amountPaise / 100}",
                    timestamp = paidAt
                )
            )
            saved = SavedCollection(receiptNo, amountPaise, mode, businessDate, receipt.referenceNo)
        }
        return checkNotNull(saved)
    }

    /**
     * Cancels a saved collection that was entered wrongly. The receipt stays on record as VOIDED with
     * the reason and time (so the audit trail is intact and the same request can never be replayed from
     * the cloud), but the allocations it created are removed, so dues, totals and passbooks no longer
     * count it. Returns the receipt so the caller can also mark it voided in the cloud.
     */
    fun voidReceipt(db: AppDatabase, receiptId: String, reason: String, now: Long = System.currentTimeMillis()): CollectionReceiptEntity {
        require(reason.trim().length >= 4) { "Enter a reason for voiding this receipt" }
        var receipt: CollectionReceiptEntity? = null
        db.runInTransaction {
            val found = db.collectionReceiptDao().getByIdSync(receiptId) ?: throw IllegalStateException("Receipt was not found")
            check(found.status == "SAVED") { "This receipt is already voided" }
            db.paymentDao().deleteByReceipt(found.receiptNo, found.memberId, found.groupId)
            check(db.collectionReceiptDao().markVoided(found.id, reason.trim(), now) == 1) { "This receipt is already voided" }
            db.activityLogDao().insertLog(
                ActivityLogEntity(
                    actionType = "RECEIPT_VOIDED", title = "Receipt voided",
                    description = "${found.receiptNo} • ${found.memberId} • ₹${found.amountPaidPaise / 100} • ${reason.trim()}",
                    timestamp = now
                )
            )
            receipt = found
        }
        return checkNotNull(receipt)
    }

    /** Same as [voidReceipt] but looked up by the collection's request id (used when a void arrives from the cloud). */
    fun voidByRequestId(db: AppDatabase, requestId: String, reason: String): Boolean {
        val receipt = db.collectionReceiptDao().getByRequestIdSync(requestId) ?: return false
        if (receipt.status != "SAVED") return false
        voidReceipt(db, receipt.id, reason.ifBlank { "Voided by admin" })
        return true
    }

    private fun paymentLine(memberId: String, groupId: String, installmentId: String, amount: Long, mode: String, reference: String?, receipt: String, paidAt: Long, status: String, collectedBy: String? = null, collectedByAgentId: String? = null) =
        PaymentEntity().apply {
            id = UUID.randomUUID().toString()
            this.memberId = memberId
            this.groupId = groupId
            this.installmentId = installmentId
            amountPaid = amount
            this.mode = mode
            referenceNo = reference?.trim()
            receiptNo = receipt
            this.paidAt = paidAt
            this.status = status
            this.collectedBy = collectedBy
            this.collectedByAgentId = collectedByAgentId
        }

    /**
     * How many installments have fallen due as of [asOf]. Installment N is due on the group's
     * start date plus (N - 1) months, so a chit that starts on the 25th only owes its second
     * installment from the 25th of the next month - not from the 1st, which is what counting
     * whole calendar months used to do (it showed members as pending weeks early).
     */
    private fun installmentsDue(start: String?, asOf: Date, duration: Int): Int {
        val startDate = parseDate(start) ?: return 1.coerceAtMost(duration)
        val from = Calendar.getInstance().apply { time = startDate }
        val to = Calendar.getInstance().apply { time = asOf }
        var months = (to.get(Calendar.YEAR) - from.get(Calendar.YEAR)) * 12 + to.get(Calendar.MONTH) - from.get(Calendar.MONTH)
        if (months < 0) return 0
        val candidateDue = Calendar.getInstance().apply { time = startDate; add(Calendar.MONTH, months) }.time
        if (dayStart(candidateDue).after(dayStart(asOf))) months -= 1
        return (months + 1).coerceIn(0, duration)
    }

    private fun parseDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        return listOf("dd-MMM-yyyy", "dd MMM yyyy", "yyyy-MM-dd", "dd-MM-yyyy").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }.parse(value) }.getOrNull()
        }
    }

    private fun dayStart(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun Long?.orZero(): Long = this ?: 0L

    /**
     * The amount a member owes for one installment. Fixed-schedule plans (50K/1L/2L/3L/10L)
     * pay a different amount every month by design, so once a group matches one of those
     * plans, that month's plan amount always wins - a member's stored flat installmentAmountPaise
     * (auto-filled as chitValue/months when they were added, back when every plan was flat) is
     * ignored for those groups instead of silently overriding the real varying schedule. Only
     * groups without a matching fixed schedule (custom/flat chit values) still honor that
     * per-member override.
     */
    private fun scheduledAmountPaise(group: ChitGroupEntity, membershipOverridePaise: Long?, base: Int, kasaru: Int?): Long {
        val flatFallback = (base - (kasaru ?: 0)).toLong()
        val hasFixedSchedule = ChitTemplate.forChitValue(group.chitValue / 100)?.fixedSchedule?.size == group.durationMonths
        return if (hasFixedSchedule) flatFallback else (membershipOverridePaise?.takeIf { it > 0 } ?: flatFallback)
    }
}
