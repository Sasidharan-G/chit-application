package com.jothivel.chits.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.jothivel.chits.data.local.entity.CollectionReceiptEntity;
import java.util.List;

@Dao
public interface CollectionReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(CollectionReceiptEntity receipt);

    @Query("SELECT * FROM collection_receipts WHERE id = :id LIMIT 1")
    CollectionReceiptEntity getByIdSync(String id);

    @Query("SELECT * FROM collection_receipts WHERE memberId = :memberId ORDER BY paidAt DESC")
    List<CollectionReceiptEntity> getForMemberSync(String memberId);

    @Query("SELECT * FROM collection_receipts WHERE status = 'VOIDED'")
    List<CollectionReceiptEntity> getVoidedSync();

    @Query("UPDATE collection_receipts SET status = 'VOIDED', voidReason = :reason, voidedAt = :at WHERE id = :id AND status = 'SAVED'")
    int markVoided(String id, String reason, long at);

    @Query("DELETE FROM collection_receipts")
    void deleteAll();

    @Query("SELECT * FROM collection_receipts WHERE receiptNo = :receiptNo LIMIT 1")
    CollectionReceiptEntity getByReceiptNoSync(String receiptNo);

    @Query("SELECT * FROM collection_receipts WHERE requestId = :requestId LIMIT 1")
    CollectionReceiptEntity getByRequestIdSync(String requestId);

    @Query("SELECT * FROM collection_receipts ORDER BY paidAt DESC LIMIT :limit")
    List<CollectionReceiptEntity> getRecentSync(int limit);

    @Query("SELECT * FROM collection_receipts WHERE paidAt >= :startMillis AND paidAt < :endMillis AND status = 'SAVED'")
    List<CollectionReceiptEntity> getSavedForTimeRangeSync(long startMillis, long endMillis);

    @Query("SELECT COALESCE(SUM(amountPaidPaise), 0) FROM collection_receipts WHERE businessDate = :businessDate AND status = 'SAVED'")
    long getTotalForDateSync(String businessDate);

    @Query("SELECT COUNT(*) FROM collection_receipts WHERE businessDate = :businessDate AND status = 'SAVED'")
    int getCountForDateSync(String businessDate);

    // Real-clock "today" (paidAt-based), matching the labour app's own Today summary - used
    // wherever admin screens need to agree with what the field agent actually sees as "today",
    // instead of the separately-editable businessDate field on the receipt.
    @Query("SELECT COALESCE(SUM(amountPaidPaise), 0) FROM collection_receipts WHERE paidAt >= :startMillis AND paidAt < :endMillis AND status = 'SAVED'")
    long getTotalForTimeRangeSync(long startMillis, long endMillis);

    @Query("SELECT COUNT(*) FROM collection_receipts WHERE paidAt >= :startMillis AND paidAt < :endMillis AND status = 'SAVED'")
    int getCountForTimeRangeSync(long startMillis, long endMillis);
}
