package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GrowthPhotoDao {
    // Scoped by gardenId too — see ExtraPhotoDao.getForPlant for why.
    @Query("SELECT * FROM growth_photos WHERE plantId = :plantId AND gardenId = :gardenId ORDER BY takenAt ASC")
    fun getForPlant(plantId: String, gardenId: String): Flow<List<GrowthPhotoEntity>>

    @Query("SELECT DISTINCT plantId FROM growth_photos WHERE gardenId = :gardenId")
    fun getAllPlantIdsWithPhotos(gardenId: String): Flow<List<String>>

    @Query("SELECT * FROM growth_photos")
    suspend fun getAllOnce(): List<GrowthPhotoEntity>

    @Query("SELECT * FROM growth_photos WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<GrowthPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: GrowthPhotoEntity)

    @Query("DELETE FROM growth_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM growth_photos WHERE plantId = :plantId")
    suspend fun deleteForPlant(plantId: String)

    @Query("DELETE FROM growth_photos WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)

    @Query("SELECT * FROM growth_photos WHERE id = :id")
    suspend fun getById(id: String): GrowthPhotoEntity?

    /** See PlantDao.syncFingerprint — lets a photo edit trigger a sync just like a plant edit. */
    @Query("SELECT COUNT(*) || ':' || IFNULL(SUM(updatedAt), 0) FROM growth_photos WHERE gardenId = :gardenId")
    fun syncFingerprint(gardenId: String): Flow<String>
    @Query("SELECT COUNT(*) || ':' || IFNULL(SUM(updatedAt), 0) FROM growth_photos WHERE gardenId = :gardenId")
    suspend fun syncFingerprintOnce(gardenId: String): String
}
