package com.example.dansgardenmapper

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GrowthPhotoDao {
    @Query("SELECT * FROM growth_photos WHERE plantId = :plantId ORDER BY takenAt ASC")
    fun getForPlant(plantId: String): Flow<List<GrowthPhotoEntity>>

    @Query("SELECT DISTINCT plantId FROM growth_photos")
    fun getAllPlantIdsWithPhotos(): Flow<List<String>>

    @Query("SELECT * FROM growth_photos")
    suspend fun getAllOnce(): List<GrowthPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: GrowthPhotoEntity)

    @Query("DELETE FROM growth_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM growth_photos WHERE plantId = :plantId")
    suspend fun deleteForPlant(plantId: String)
}