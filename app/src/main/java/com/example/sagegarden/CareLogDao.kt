package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CareLogDao {
    // Scoped by gardenId too, not just plantId — plant ids like "P0001" are generated fresh per
    // garden and can collide across different gardens sharing this same device (see
    // PlantViewModel.getAllPlantsOnDevice), which would otherwise leak one garden's plant's care
    // history onto a different garden's identically-numbered plant.
    @Query("SELECT * FROM care_log WHERE plantId = :plantId AND gardenId = :gardenId ORDER BY date DESC")
    fun getForPlant(plantId: String, gardenId: String): Flow<List<CareLogEntity>>

    @Query("SELECT * FROM care_log")
    suspend fun getAllOnce(): List<CareLogEntity>

    @Query("SELECT * FROM care_log WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<CareLogEntity>

    @Query("SELECT * FROM care_log WHERE id = :id")
    suspend fun getById(id: String): CareLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CareLogEntity)

    @Query("DELETE FROM care_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM care_log WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)
}