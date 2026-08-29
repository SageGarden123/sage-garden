package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WaterFlowRateDao {
    @Query("SELECT * FROM water_flow_rates WHERE gardenId = :gardenId")
    fun getAll(gardenId: String): Flow<List<WaterFlowRateEntity>>

    @Query("SELECT * FROM water_flow_rates WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<WaterFlowRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WaterFlowRateEntity)

    @Query("DELETE FROM water_flow_rates WHERE gardenId = :gardenId AND zone = :zone AND outlet = :outlet")
    suspend fun deleteByZoneOutlet(gardenId: String, zone: String, outlet: String)

    @Query("DELETE FROM water_flow_rates WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)
}
