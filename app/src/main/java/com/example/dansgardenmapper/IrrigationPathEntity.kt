package com.example.dansgardenmapper

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "irrigation_paths")
data class IrrigationPathEntity(
    @PrimaryKey val id: String,
    val zone: String,                 // free-text zone/watering-system name — match a Tuya zone name for consistent colour-grouping
    val waypointsJson: String,        // "[[x1,y1],[x2,y2],...]" — fractions 0.0-1.0 across the custom map image
    val targetPlantIds: String        // comma-separated PlantEntity ids this path waters
)

@Dao
interface IrrigationPathDao {
    @Query("SELECT * FROM irrigation_paths")
    fun getAll(): Flow<List<IrrigationPathEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(path: IrrigationPathEntity)

    @Query("DELETE FROM irrigation_paths WHERE id = :id")
    suspend fun deleteById(id: String)
}