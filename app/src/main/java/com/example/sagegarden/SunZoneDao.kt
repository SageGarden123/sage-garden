package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SunZoneDao {
    @Query("SELECT * FROM sun_zones WHERE gardenId = :gardenId")
    fun getAll(gardenId: String): Flow<List<SunZoneEntity>>

    @Query("SELECT * FROM sun_zones WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<SunZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: SunZoneEntity)

    @Query("DELETE FROM sun_zones WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sun_zones WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)
}