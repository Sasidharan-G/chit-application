package com.jothivel.chits.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.jothivel.chits.data.local.entity.ChitGroupEntity;

import java.util.List;

@Dao
public interface GroupDao {
    @Query("SELECT * FROM chit_groups")
    LiveData<List<ChitGroupEntity>> getAllGroups();

    @Query("SELECT * FROM chit_groups")
    List<ChitGroupEntity> getAllGroupsSync();

    @Query("SELECT * FROM chit_groups WHERE id = :id LIMIT 1")
    LiveData<ChitGroupEntity> getGroupById(String id);

    @Query("SELECT * FROM chit_groups WHERE id = :id LIMIT 1")
    ChitGroupEntity getGroupByIdSync(String id);

    @Query("SELECT * FROM chit_groups WHERE name = :name LIMIT 1")
    ChitGroupEntity getGroupByNameSync(String name);

    @Query("SELECT * FROM chit_groups WHERE name = :name")
    List<ChitGroupEntity> getGroupsByNameSync(String name);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ChitGroupEntity> groups);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroup(ChitGroupEntity group);

    /** Inserts only rows that are not already present; existing local rows are left untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllIgnore(List<ChitGroupEntity> groups);

    @Query("SELECT * FROM chit_groups WHERE UPPER(registerNo) = UPPER(:registerNo) LIMIT 1")
    ChitGroupEntity getGroupByRegisterNoSync(String registerNo);

    @Query("UPDATE chit_groups SET status = :status WHERE id = :id")
    int updateStatus(String id, String status);

    @Query("UPDATE chit_groups SET name = :name, registerNo = :registerNo, branch = :branch, startDate = :startDate, subscriberCount = :subscriberCount WHERE id = :id")
    int updateDetails(String id, String name, String registerNo, String branch, String startDate, int subscriberCount);

    @Query("SELECT COUNT(*) FROM chit_groups")
    LiveData<Integer> getGroupCount();
}
