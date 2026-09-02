package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualZoneScheduleDao {
    @Query("SELECT * FROM manual_zone_schedules WHERE zone = :zone AND gardenId = :gardenId ORDER BY createdAt ASC")
    fun getForZone(zone: String, gardenId: String): Flow<List<ManualZoneScheduleEntity>>

    @Query("SELECT * FROM manual_zone_schedules WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<ManualZoneScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ManualZoneScheduleEntity)

    @Query("DELETE FROM manual_zone_schedules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM manual_zone_schedules WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)
}
