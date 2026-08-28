package com.example.sagegarden

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {
    @Query("SELECT * FROM plants WHERE gardenId = :gardenId ORDER BY name ASC")
    fun getAll(gardenId: String): Flow<List<PlantEntity>>

    @Query("SELECT * FROM plants WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<PlantEntity>

    @Query("SELECT * FROM plants WHERE id = :id")
    suspend fun getById(id: String): PlantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plant: PlantEntity)

    @Query("DELETE FROM plants WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM plants")
    suspend fun deleteAll()

    @Query("DELETE FROM plants WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)

    @Query("SELECT * FROM plants")
    suspend fun getAllOnce(): List<PlantEntity>
}
