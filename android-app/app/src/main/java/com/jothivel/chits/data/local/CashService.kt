package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.CashHandoverEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Cash a field agent has collected, handed over so far, and still holds. All paise. */
data class AgentCashSummary(val agentId: String, val agentName: String, val collectedPaise: Long, val handedOverPaise: Long) {
    val outstandingPaise: Long get() = collectedPaise - handedOverPaise
}

/**
 * One business day's money: what came in (cash taken at the office, cash agents collected, UPI,
 * bank), agent cash handed over to the office, and what was paid out. Cash an agent has collected
 * but NOT yet handed over is reported per agent instead.
 */
data class DailyCashSummary(
    val dateKey: String,
    val directCashPaise: Long,
    val agentHandoverPaise: Long,
    val upiPaise: Long,
    val bankPaise: Long,
    val allCashCollectedPaise: Long,
    val settlementPaise: Long,
    val deliveryPaise: Long
)

/** Agent cash-handover tracking and the day's money summary shown on Daily Closing. */
object CashService {
    private val keyFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    // ── Agent cash handovers ─────────────────────────────────────────────────────────────

    fun agentSummaries(db: AppDatabase): List<AgentCashSummary> =
        db.paymentDao().getCashCollectedPerAgentSync().map { row ->
            AgentCashSummary(
                agentId = row.agentId,
                agentName = row.agentName.orEmpty().ifBlank { row.agentId },
                collectedPaise = row.totalPaise,
                handedOverPaise = db.cashHandoverDao().getPostedTotalForAgentSync(row.agentId)
            )
        }.sortedWith(compareByDescending<AgentCashSummary> { it.outstandingPaise }.thenBy { it.agentName.lowercase() })

    fun outstandingPaise(db: AppDatabase, agentId: String): Long =
        db.paymentDao().getCashCollectedByAgentSync(agentId) - db.cashHandoverDao().getPostedTotalForAgentSync(agentId)

    /**
     * Records cash an agent handed over. Idempotent on [requestId]. A handover can never exceed
     * what the agent has actually collected and not yet handed over.
     */
    fun recordHandover(db: AppDatabase, requestId: String, agentId: String, agentName: String, amountPaise: Long, notes: String, now: Long = System.currentTimeMillis()): String {
        require(amountPaise > 0) { "Enter a valid amount" }
        require(agentId.isNotBlank()) { "Select the agent" }
        db.cashHandoverDao().getByRequestIdSync(requestId)?.let { return it.id }
        var id = ""
        db.runInTransaction {
            val outstanding = outstandingPaise(db, agentId)
            require(amountPaise <= outstanding) { "This agent only holds ₹${maxOf(outstanding, 0) / 100} - the handover cannot be more than that" }
            id = UUID.randomUUID().toString()
            db.cashHandoverDao().insert(CashHandoverEntity().apply {
                this.id = id
                this.requestId = requestId
                this.agentId = agentId
                this.agentName = agentName.ifBlank { agentId }
                this.amountPaise = amountPaise
                handedAt = now
                businessDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                this.notes = notes.trim().ifBlank { null }
                status = "POSTED"
                reversalReason = null
                reversedAt = null
            })
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "CASH_HANDOVER", title = "Agent cash handed over", description = "${agentName.ifBlank { agentId }} • ₹${amountPaise / 100}", timestamp = now))
        }
        return id
    }

    fun reverseHandover(db: AppDatabase, id: String, reason: String) {
        require(reason.trim().length >= 4) { "Enter a reversal reason" }
        val now = System.currentTimeMillis()
        db.runInTransaction {
            check(db.cashHandoverDao().reversePosted(id, reason.trim(), now) == 1) { "Entry was already reversed or not found" }
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "CASH_HANDOVER_REVERSED", title = "Cash handover reversed", description = "$id • ${reason.trim()}", timestamp = now))
        }
    }

    // ── Daily summary ────────────────────────────────────────────────────────────────────

    private fun dayRange(dateKey: String): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            time = requireNotNull(runCatching { keyFormat.parse(dateKey) }.getOrNull()) { "Date must look like 2026-01-31" }
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun isCash(mode: String?) = mode.equals("Cash", ignoreCase = true)
    private fun isUpi(mode: String?) = mode.equals("UPI", ignoreCase = true)
    private fun isBank(mode: String?) = mode.equals("Bank", ignoreCase = true) || mode.equals("BANK_TRANSFER", ignoreCase = true)

    fun dailySummary(db: AppDatabase, dateKey: String): DailyCashSummary {
        val (start, end) = dayRange(dateKey)
        val receipts = db.collectionReceiptDao().getSavedForTimeRangeSync(start, end)
        val payouts = db.financialTransactionDao().getPostedForTimeRangeSync(start, end)
        return DailyCashSummary(
            dateKey = dateKey,
            directCashPaise = db.paymentDao().getDirectCashForTimeRangeSync(start, end),
            agentHandoverPaise = db.cashHandoverDao().getPostedTotalForTimeRangeSync(start, end),
            upiPaise = receipts.filter { isUpi(it.mode) }.sumOf { it.amountPaidPaise },
            bankPaise = receipts.filter { isBank(it.mode) }.sumOf { it.amountPaidPaise },
            allCashCollectedPaise = receipts.filter { isCash(it.mode) }.sumOf { it.amountPaidPaise },
            settlementPaise = payouts.filter { it.type == "SETTLEMENT" }.sumOf { it.amountPaise },
            deliveryPaise = payouts.filter { it.type == "DELIVERY" }.sumOf { it.amountPaise }
        )
    }
}
