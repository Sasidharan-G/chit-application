package com.jothivel.chits.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.jothivel.chits.data.local.entity.CashHandoverEntity;
import java.util.List;

@Dao
public interface CashHandoverDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(CashHandoverEntity handover);

    @Query("SELECT * FROM cash_handovers ORDER BY handedAt DESC")
    List<CashHandoverEntity> getAllSync();

    @Query("SELECT * FROM cash_handovers WHERE agentId = :agentId ORDER BY handedAt DESC")
    List<CashHandoverEntity> getForAgentSync(String agentId);

    @Query("SELECT * FROM cash_handovers WHERE requestId = :requestId LIMIT 1")
    CashHandoverEntity getByRequestIdSync(String requestId);

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM cash_handovers WHERE agentId = :agentId AND status = 'POSTED'")
    long getPostedTotalForAgentSync(String agentId);

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM cash_handovers WHERE handedAt >= :startMillis AND handedAt < :endMillis AND status = 'POSTED'")
    long getPostedTotalForTimeRangeSync(long startMillis, long endMillis);

    @Query("UPDATE cash_handovers SET status = 'REVERSED', reversalReason = :reason, reversedAt = :reversedAt WHERE id = :id AND status = 'POSTED'")
    int reversePosted(String id, String reason, long reversedAt);
}
