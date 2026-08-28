package com.example.sagegarden

import androidx.room.Entity

/**
 * Calibrated via a jug-fill test: how long it takes to fill a known volume from a given zone's outlet.
 * gardenId is part of the primary key, not just a filter column — without it, two gardens that both
 * happen to name a zone "Front" would upsert onto the very same row (REPLACE keys off the primary
 * key alone), silently overwriting one garden's calibration with the other's.
 */
@Entity(tableName = "water_flow_rates", primaryKeys = ["gardenId", "zone", "outlet"])
data class WaterFlowRateEntity(
    val zone: String,
    val outlet: String,
    val litersPerMinute: Double,
    val gardenId: String = ""
)
