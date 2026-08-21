package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WaterFlowRateDao {
    @Query("SELECT * FROM water_flow_rates")
    fun getAll(): Flow<List<WaterFlowRateEntity>>

    @Query("SELECT * FROM water_flow_rates")
    suspend fun getAllOnce(): List<WaterFlowRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WaterFlowRateEntity)

    @Query("DELETE FROM water_flow_rates WHERE zone = :zone AND outlet = :outlet")
    suspend fun deleteByZoneOutlet(zone: String, outlet: String)
}
