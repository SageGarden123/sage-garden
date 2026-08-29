package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LocationPhotoCount(val location: String, val count: Int)

@Dao
interface LocationPhotoDao {
    // Scoped by gardenId too — location NAMES can legitimately collide across gardens (e.g. two
    // gardens both naming a zone "Back garden"), which would otherwise leak one garden's progress
    // photos onto a different garden's identically-named location.
    @Query("SELECT * FROM location_photos WHERE location = :location AND gardenId = :gardenId ORDER BY takenAt ASC")
    fun getForLocation(location: String, gardenId: String): Flow<List<LocationPhotoEntity>>

    @Query("SELECT location, COUNT(*) as count FROM location_photos WHERE gardenId = :gardenId GROUP BY location")
    fun getLocationPhotoCounts(gardenId: String): Flow<List<LocationPhotoCount>>

    @Query("SELECT * FROM location_photos")
    suspend fun getAllOnce(): List<LocationPhotoEntity>

    @Query("SELECT * FROM location_photos WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<LocationPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: LocationPhotoEntity)

    @Query("DELETE FROM location_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM location_photos WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)
}
