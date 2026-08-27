package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LocationPhotoCount(val location: String, val count: Int)

@Dao
interface LocationPhotoDao {
    @Query("SELECT * FROM location_photos WHERE location = :location ORDER BY takenAt ASC")
    fun getForLocation(location: String): Flow<List<LocationPhotoEntity>>

    @Query("SELECT location, COUNT(*) as count FROM location_photos GROUP BY location")
    fun getLocationPhotoCounts(): Flow<List<LocationPhotoCount>>

    @Query("SELECT * FROM location_photos")
    suspend fun getAllOnce(): List<LocationPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: LocationPhotoEntity)

    @Query("DELETE FROM location_photos WHERE id = :id")
    suspend fun deleteById(id: String)
}
