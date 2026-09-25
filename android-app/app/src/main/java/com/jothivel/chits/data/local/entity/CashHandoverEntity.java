package com.jothivel.chits.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Cash a field agent (labour) handed over to the office. Agents collect physical cash all day;
 * without this record there is no way to tell how much of it has actually reached the office.
 * Outstanding cash per agent = (cash collected by the agent) - (sum of POSTED handovers).
 * Rows are never edited or deleted: a wrong entry is REVERSED with a reason, like
 * [FinancialTransactionEntity].
 */
@Entity(tableName = "cash_handovers", indices = {
        @Index(value = {"requestId"}, unique = true),
        @Index(value = {"agentId", "status"})
})
public class CashHandoverEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String requestId;
    @NonNull public String agentId;
    @NonNull public String agentName;
    public long amountPaise;
    public long handedAt;
    @NonNull public String businessDate;
    public String notes;
    @NonNull public String status; // POSTED | REVERSED
    public String reversalReason;
    public Long reversedAt;
}
