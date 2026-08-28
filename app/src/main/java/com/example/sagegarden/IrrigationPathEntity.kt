package com.example.sagegarden

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
    val zone: String,           // free-text zone name — match a Tuya zone name for consistent colour-grouping
    val outletX: Double,        // fraction 0.0-1.0 — where the tap/outlet starts on the custom map image
    val outletY: Double,
    val segmentsJson: String,   // JSON array of {type:"main"|"drip", points:[[x,y],...], targets:[plantId,...]}
    val gardenId: String = ""  // blank means "not yet stamped" — filled in by IrrigationPathViewModel.save
)

@Dao
interface IrrigationPathDao {
    @Query("SELECT * FROM irrigation_paths WHERE gardenId = :gardenId")
    fun getAll(gardenId: String): Flow<List<IrrigationPathEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(path: IrrigationPathEntity)

    @Query("DELETE FROM irrigation_paths WHERE id = :id")
    suspend fun deleteById(id: String)
}