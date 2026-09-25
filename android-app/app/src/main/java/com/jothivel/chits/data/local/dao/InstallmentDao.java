package com.jothivel.chits.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.jothivel.chits.data.local.entity.InstallmentEntity;

import java.util.List;

@Dao
public interface InstallmentDao {

    @Query("SELECT * FROM installments WHERE groupId = :groupId ORDER BY installmentNo ASC")
    LiveData<List<InstallmentEntity>> getInstallmentsForGroup(String groupId);

    @Query("SELECT * FROM installments WHERE groupId = :groupId ORDER BY installmentNo ASC")
    List<InstallmentEntity> getInstallmentsForGroupSync(String groupId);

    @Query("SELECT * FROM installments WHERE groupId = :groupId AND installmentNo = :installmentNo LIMIT 1")
    InstallmentEntity getByNumberSync(String groupId, int installmentNo);

    @Query("SELECT * FROM installments WHERE groupId = :groupId AND winningMemberId = :memberId ORDER BY installmentNo ASC")
    List<InstallmentEntity> getWonByMemberSync(String groupId, String memberId);

    @Query("UPDATE installments SET winningMemberId = :memberId, payoutAmount = :payoutPaise, auctionDate = :auctionDate, status = 'AUCTION_DONE' WHERE id = :id AND winningMemberId IS NULL")
    int setWinnerIfUnset(String id, String memberId, int payoutPaise, String auctionDate);

    @Query("UPDATE installments SET winningMemberId = NULL, auctionDate = NULL, status = 'UPCOMING' WHERE id = :id")
    int clearWinner(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<InstallmentEntity> installments);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(InstallmentEntity installment);

    /** Inserts only rows that are not already present; existing local rows are left untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllIgnore(List<InstallmentEntity> installments);

    @Update
    void update(InstallmentEntity installment);
}
