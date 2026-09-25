package com.jothivel.chits.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.jothivel.chits.data.local.entity.MemberEntity;

import java.util.List;

@Dao
public interface MemberDao {
    @Query("SELECT * FROM members ORDER BY name ASC")
    LiveData<List<MemberEntity>> getAllMembers();

    @Query("SELECT * FROM members ORDER BY name ASC")
    List<MemberEntity> getAllMembersSync();

    @Query("SELECT * FROM members WHERE selectedChitId = :chitId ORDER BY name ASC")
    LiveData<List<MemberEntity>> getMembersByChitId(String chitId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<MemberEntity> members);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMember(MemberEntity member);

    /** Inserts only rows that are not already present; existing local rows are left untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllIgnore(List<MemberEntity> members);

    @Query("UPDATE members SET name = :value WHERE id = :memberId")
    void updateName(String memberId, String value);

    @Query("UPDATE members SET phone = :value WHERE id = :memberId")
    void updatePhone(String memberId, String value);

    @Query("UPDATE members SET panNo = :value WHERE id = :memberId")
    void updateOldCode(String memberId, String value);

    @Query("UPDATE members SET addressLine = :value WHERE id = :memberId")
    void updateAddress(String memberId, String value);

    @Query("UPDATE members SET city = :value WHERE id = :memberId")
    void updateCity(String memberId, String value);

    @Query("SELECT * FROM members WHERE id = :memberId LIMIT 1")
    LiveData<MemberEntity> getMemberById(String memberId);

    @Query("SELECT * FROM members WHERE id = :memberId LIMIT 1")
    MemberEntity getMemberByIdSync(String memberId);

    @Query("SELECT * FROM members WHERE UPPER(id) = UPPER(:memberId) LIMIT 1")
    MemberEntity getMemberByIdIgnoreCaseSync(String memberId);

    @Query("SELECT * FROM members WHERE phone = :phone LIMIT 1")
    MemberEntity getMemberByPhoneSync(String phone);

    @androidx.room.Update
    void updateMember(MemberEntity member);

    @Query("SELECT COUNT(*) FROM members")
    LiveData<Integer> getMemberCount();

    @Query("SELECT COUNT(*) FROM members WHERE selectedChitId = :chitId")
    LiveData<Integer> getMemberCountByChitId(String chitId);
}
