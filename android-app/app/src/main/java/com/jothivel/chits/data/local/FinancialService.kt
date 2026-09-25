package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.FinancialTransactionEntity
import java.util.UUID

object FinancialService {
    val allowedTypes = setOf("SETTLEMENT", "DELIVERY")

    fun record(db: AppDatabase, requestId: String, type: String, memberId: String, groupId: String, amountPaise: Long, mode: String, reference: String?, notes: String): String {
        require(type in allowedTypes) { "Unsupported transaction type" }
        require(amountPaise > 0) { "Enter a valid amount" }
        require(mode == "Cash" || !reference.isNullOrBlank()) { "Reference / UTR is required" }
        require(db.membershipDao().getSync(memberId, groupId)?.isActive == true) { "Customer is not active in this chit" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.runInTransaction {
            if (type == "DELIVERY") checkDeliveryAgainstWinner(db, memberId, groupId, amountPaise)
            db.financialTransactionDao().insert(FinancialTransactionEntity().apply {
                this.id=id; this.requestId=requestId; this.type=type; this.memberId=memberId; this.groupId=groupId
                this.amountPaise=amountPaise; this.mode=mode; referenceNo=reference?.trim()?.ifBlank { null }
                this.notes=notes.trim(); occurredAt=now; status="POSTED"; reversalReason=null; reversedAt=null
            })
            db.activityLogDao().insertLog(ActivityLogEntity(actionType="${type}_RECORDED", title="${type.lowercase().replaceFirstChar(Char::uppercase)} recorded", description="$memberId • $groupId • ₹${amountPaise/100}", timestamp=now))
        }
        return id
    }

    /**
     * A Delivery is the prize money handed to an auction winner, so it must be tied to one: the
     * customer has to be recorded as the winner of an installment in this chit, and what has been
     * delivered can never add up to more than the prize recorded for that win. (Settlement is a
     * different, end-of-chit payment and stays free-form.)
     */
    private fun checkDeliveryAgainstWinner(db: AppDatabase, memberId: String, groupId: String, amountPaise: Long) {
        val prize = ChitAdminService.prizePaise(db, memberId, groupId)
        require(db.installmentDao().getWonByMemberSync(groupId, memberId).isNotEmpty()) {
            "This customer has not been recorded as an auction winner in this chit. Record the winner from the chit's page first."
        }
        require(prize > 0) { "No payout amount is recorded for this customer's winning installment" }
        val delivered = db.financialTransactionDao().getPostedTotalForMemberGroupTypeSync(memberId, groupId, "DELIVERY")
        val remaining = prize - delivered
        require(amountPaise <= remaining) {
            "Delivery is more than the payout still due: ₹${maxOf(remaining, 0) / 100} left of ₹${prize / 100} (₹${delivered / 100} already delivered)"
        }
    }

    fun reverse(db: AppDatabase, id: String, reason: String) {
        require(reason.trim().length >= 4) { "Enter a reversal reason" }
        val now=System.currentTimeMillis()
        db.runInTransaction {
            check(db.financialTransactionDao().reversePosted(id, reason.trim(), now) == 1) { "Entry was already reversed or not found" }
            db.activityLogDao().insertLog(ActivityLogEntity(actionType="FINANCIAL_REVERSED", title="Financial entry reversed", description="$id • ${reason.trim()}", timestamp=now))
        }
    }
}
