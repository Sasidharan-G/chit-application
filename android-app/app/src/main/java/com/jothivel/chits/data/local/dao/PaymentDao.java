package com.jothivel.chits.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.jothivel.chits.data.local.entity.PaymentEntity;

import java.util.List;

@Dao
public interface PaymentDao {
    @Query("SELECT * FROM payments WHERE memberId = :memberId ORDER BY paidAt DESC")
    LiveData<List<PaymentEntity>> getPaymentsByMember(String memberId);

    @Query("SELECT * FROM payments WHERE memberId = :memberId ORDER BY paidAt DESC")
    List<PaymentEntity> getPaymentsByMemberSync(String memberId);

    @Query("SELECT * FROM payments WHERE memberId = :memberId AND groupId = :groupId ORDER BY paidAt DESC")
    List<PaymentEntity> getPaymentsByMemberAndGroupSync(String memberId, String groupId);

    @Query("SELECT * FROM payments ORDER BY paidAt DESC")
    List<PaymentEntity> getAllPaymentsSync();

    @Query("SELECT * FROM payments WHERE memberId = :memberId AND groupId = :groupId AND installmentId = :installmentId")
    List<PaymentEntity> getPaymentsForInstallmentSync(String memberId, String groupId, String installmentId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPayment(PaymentEntity payment);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<PaymentEntity> payments);

    @Query("SELECT * FROM payments WHERE id = :id LIMIT 1")
    PaymentEntity getByIdSync(String id);

    @Query("SELECT * FROM payments WHERE groupId = :groupId")
    List<PaymentEntity> getPaymentsForGroupSync(String groupId);

    @Query("SELECT COUNT(*) FROM payments WHERE groupId = :groupId")
    int countForGroupSync(String groupId);

    // Physical cash each field agent has collected (all time). Together with the POSTED cash
    // handovers this gives the cash still sitting with the agent.
    @Query("SELECT collectedByAgentId AS agentId, MAX(collectedBy) AS agentName, SUM(amountPaid) AS totalPaise FROM payments WHERE collectedByAgentId IS NOT NULL AND collectedByAgentId != '' AND UPPER(mode) = 'CASH' GROUP BY collectedByAgentId")
    List<AgentCashRow> getCashCollectedPerAgentSync();

    // Cash the office itself took in [startMillis, endMillis) - agent-collected cash only reaches
    // the drawer when the agent hands it over (see CashHandoverDao), so it is excluded here.
    @Query("SELECT COALESCE(SUM(amountPaid), 0) FROM payments WHERE paidAt >= :startMillis AND paidAt < :endMillis AND UPPER(mode) = 'CASH' AND (collectedByAgentId IS NULL OR collectedByAgentId = '') AND receiptNo NOT LIKE 'IMP-%'")
    long getDirectCashForTimeRangeSync(long startMillis, long endMillis);

    @Query("SELECT COALESCE(SUM(amountPaid), 0) FROM payments WHERE collectedByAgentId = :agentId AND UPPER(mode) = 'CASH'")
    long getCashCollectedByAgentSync(String agentId);

    @Query("DELETE FROM payments WHERE receiptNo = :receiptNo AND memberId = :memberId AND groupId = :groupId")
    int deleteByReceipt(String receiptNo, String memberId, String groupId);

    @Query("DELETE FROM payments WHERE memberId = :memberId AND groupId = :groupId")
    void deleteForMemberAndGroup(String memberId, String groupId);
}
