package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtraPhotoDao {
    @Query("SELECT * FROM extra_photos WHERE plantId = :plantId ORDER BY addedAt ASC")
    fun getForPlant(plantId: String): Flow<List<ExtraPhotoEntity>>

    @Query("SELECT * FROM extra_photos")
    suspend fun getAllOnce(): List<ExtraPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: ExtraPhotoEntity)

    @Query("DELETE FROM extra_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM extra_photos WHERE plantId = :plantId")
    suspend fun deleteForPlant(plantId: String)
}
