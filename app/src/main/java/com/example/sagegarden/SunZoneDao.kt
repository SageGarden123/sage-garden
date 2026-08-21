package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SunZoneDao {
    @Query("SELECT * FROM sun_zones")
    fun getAll(): Flow<List<SunZoneEntity>>

    @Query("SELECT * FROM sun_zones")
    suspend fun getAllOnce(): List<SunZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: SunZoneEntity)

    @Query("DELETE FROM sun_zones WHERE id = :id")
    suspend fun deleteById(id: String)
}