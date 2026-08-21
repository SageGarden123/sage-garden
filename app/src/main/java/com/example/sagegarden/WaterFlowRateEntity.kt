package com.example.sagegarden

import androidx.room.Entity

/** Calibrated via a jug-fill test: how long it takes to fill a known volume from a given zone's outlet. */
@Entity(tableName = "water_flow_rates", primaryKeys = ["zone", "outlet"])
data class WaterFlowRateEntity(
    val zone: String,
    val outlet: String,
    val litersPerMinute: Double
)
