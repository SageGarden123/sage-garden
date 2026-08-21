package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CareLogDao {
    @Query("SELECT * FROM care_log WHERE plantId = :plantId ORDER BY date DESC")
    fun getForPlant(plantId: String): Flow<List<CareLogEntity>>

    @Query("SELECT * FROM care_log")
    suspend fun getAllOnce(): List<CareLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CareLogEntity)

    @Query("DELETE FROM care_log WHERE id = :id")
    suspend fun deleteById(id: String)
}