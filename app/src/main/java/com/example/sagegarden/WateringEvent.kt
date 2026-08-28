package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watering_events")
data class WateringEvent(
    @PrimaryKey val id: String,
    val zone: String,           // friendly location name — matches PlantEntity.wateringSystem values
    val outlet: String = "1",   // Tuya: "1" or "2" (physical outlet on the device). Rachio: the vendor zoneId.
    val startTime: Long,        // epoch millis
    val durationMinutes: Int,
    val source: String = "Tuya",
    val gardenId: String = "" // blank means "not yet stamped" — filled in by WateringZoneViewModel on insert
)

@Dao
interface WateringEventDao {
    @Query("SELECT * FROM watering_events WHERE gardenId = :gardenId ORDER BY startTime DESC")
    fun getAll(gardenId: String): Flow<List<WateringEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<WateringEvent>)
}