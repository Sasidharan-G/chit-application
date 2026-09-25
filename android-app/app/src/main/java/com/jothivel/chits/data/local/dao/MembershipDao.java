package com.jothivel.chits.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.jothivel.chits.data.local.entity.ChitMembershipEntity;
import java.util.List;

@Dao
public interface MembershipDao {
    @Query("SELECT * FROM chit_memberships WHERE isActive = 1 ORDER BY memberId, groupId")
    List<ChitMembershipEntity> getAllActiveSync();

    @Query("SELECT * FROM chit_memberships WHERE memberId = :memberId AND isActive = 1 ORDER BY groupId")
    List<ChitMembershipEntity> getForMemberSync(String memberId);

    @Query("SELECT * FROM chit_memberships WHERE groupId = :groupId AND isActive = 1 ORDER BY ticketNo, memberId")
    List<ChitMembershipEntity> getActiveForGroupSync(String groupId);

    @Query("SELECT * FROM chit_memberships WHERE memberId = :memberId AND groupId = :groupId LIMIT 1")
    ChitMembershipEntity getSync(String memberId, String groupId);

    @Query("SELECT COUNT(*) FROM chit_memberships WHERE groupId = :groupId AND isActive = 1")
    int countActiveForGroupSync(String groupId);

    @Query("SELECT COUNT(*) FROM chit_memberships WHERE groupId = :groupId AND ticketNo = :ticketNo AND isActive = 1")
    int countActiveTicketSync(String groupId, String ticketNo);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(ChitMembershipEntity membership);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<ChitMembershipEntity> memberships);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<ChitMembershipEntity> memberships);

    @Query("SELECT * FROM chit_memberships WHERE groupId = :groupId")
    List<ChitMembershipEntity> getAllForGroupSync(String groupId);

    @Query("UPDATE chit_memberships SET isActive = :active WHERE memberId = :memberId AND groupId = :groupId")
    int setActive(String memberId, String groupId, boolean active);

    @Query("SELECT COUNT(*) FROM chit_memberships WHERE groupId = :groupId AND ticketNo = :ticketNo AND memberId != :excludeMemberId")
    int countTicketUsedByOthersSync(String groupId, String ticketNo, String excludeMemberId);

    @Query("UPDATE chit_memberships SET ticketNo = :value WHERE memberId = :memberId AND groupId = :groupId")
    void updateTicketNo(String memberId, String groupId, String value);

    @Query("UPDATE chit_memberships SET joiningDate = :value WHERE memberId = :memberId AND groupId = :groupId")
    void updateJoiningDate(String memberId, String groupId, String value);

    @Query("UPDATE chit_memberships SET dueDate = :value WHERE memberId = :memberId AND groupId = :groupId")
    void updateDueDate(String memberId, String groupId, String value);
}
