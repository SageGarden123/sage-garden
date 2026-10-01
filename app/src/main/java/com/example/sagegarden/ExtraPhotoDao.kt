package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtraPhotoDao {
    // Scoped by gardenId too, not just plantId — plant ids can (rarely, for pre-existing data from
    // before the per-garden id prefix fix) collide across gardens sharing this device, which would
    // otherwise leak one garden's extra photos onto a different garden's identically-numbered plant.
    @Query("SELECT * FROM extra_photos WHERE plantId = :plantId AND gardenId = :gardenId ORDER BY addedAt ASC")
    fun getForPlant(plantId: String, gardenId: String): Flow<List<ExtraPhotoEntity>>

    @Query("SELECT DISTINCT plantId FROM extra_photos WHERE gardenId = :gardenId")
    fun getAllPlantIdsWithPhotos(gardenId: String): Flow<List<String>>

    @Query("SELECT * FROM extra_photos")
    suspend fun getAllOnce(): List<ExtraPhotoEntity>

    @Query("SELECT * FROM extra_photos WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<ExtraPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: ExtraPhotoEntity)

    @Query("DELETE FROM extra_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM extra_photos WHERE plantId = :plantId")
    suspend fun deleteForPlant(plantId: String)

    @Query("DELETE FROM extra_photos WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)

    @Query("SELECT * FROM extra_photos WHERE id = :id")
    suspend fun getById(id: String): ExtraPhotoEntity?

    /** See PlantDao.syncFingerprint — lets a photo edit trigger a sync just like a plant edit. */
    @Query("SELECT COUNT(*) || ':' || IFNULL(SUM(updatedAt), 0) FROM extra_photos WHERE gardenId = :gardenId")
    fun syncFingerprint(gardenId: String): Flow<String>
    @Query("SELECT COUNT(*) || ':' || IFNULL(SUM(updatedAt), 0) FROM extra_photos WHERE gardenId = :gardenId")
    suspend fun syncFingerprintOnce(gardenId: String): String
}
