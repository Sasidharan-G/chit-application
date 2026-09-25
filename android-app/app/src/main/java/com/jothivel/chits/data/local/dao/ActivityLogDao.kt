package com.jothivel.chits.data.local.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jothivel.chits.data.local.entity.ActivityLogEntity

@Dao
interface ActivityLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLog(log: ActivityLogEntity)

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 20): LiveData<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAllSync(): List<ActivityLogEntity>

    @Query("SELECT * FROM activity_logs WHERE actionType = :actionType ORDER BY timestamp DESC")
    fun getByTypeSync(actionType: String): List<ActivityLogEntity>

    @Query("DELETE FROM activity_logs")
    fun clearLogs()
}
