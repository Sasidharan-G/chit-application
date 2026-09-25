package com.jothivel.chits.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.jothivel.chits.data.local.entity.FinancialTransactionEntity;
import java.util.List;

@Dao
public interface FinancialTransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(FinancialTransactionEntity entry);

    @Query("SELECT * FROM financial_transactions ORDER BY occurredAt DESC")
    List<FinancialTransactionEntity> getAllSync();

    @Query("SELECT * FROM financial_transactions WHERE status = 'POSTED' AND occurredAt >= :startMillis AND occurredAt < :endMillis")
    List<FinancialTransactionEntity> getPostedForTimeRangeSync(long startMillis, long endMillis);

    @Query("SELECT COALESCE(SUM(amountPaise),0) FROM financial_transactions WHERE memberId=:memberId AND groupId=:groupId AND type=:type AND status='POSTED'")
    long getPostedTotalForMemberGroupTypeSync(String memberId, String groupId, String type);

    @Query("SELECT * FROM financial_transactions WHERE memberId=:memberId AND groupId=:groupId AND status='POSTED' ORDER BY occurredAt DESC")
    List<FinancialTransactionEntity> getPostedForMemberGroupSync(String memberId, String groupId);

    @Query("SELECT COALESCE(SUM(amountPaise),0) FROM financial_transactions WHERE type=:type AND status='POSTED'")
    long getPostedTotalByTypeSync(String type);

    @Query("DELETE FROM financial_transactions")
    void deleteAll();

    @Query("UPDATE financial_transactions SET status='REVERSED', reversalReason=:reason, reversedAt=:reversedAt WHERE id=:id AND status='POSTED'")
    int reversePosted(String id, String reason, long reversedAt);
}
