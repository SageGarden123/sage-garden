package com.example.dansgardenmapper

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plants")
data class PlantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sci: String,
    val location: String,
    val sun: String,
    val water: String,
    val soil: String,
    val frost: String,
    val native: String,
    val pollinator: String,
    val source: String,
    val date: String,
    val qty: Int,
    val notes: String,
    val wateringSystem: String,
    val lat: Double?,
    val lng: Double?,
    val photoUri: String?,
    val photoUris: List<String> = emptyList(),
    val mapX: Double? = null,  // 0.0–1.0 fraction across the custom map image
    val mapY: Double? = null,  // 0.0–1.0 fraction down the custom map image
    val lastWateredDate: Long? = null,      // epoch millis, UTC midnight of the date picked
    val wateringFrequencyDays: Int? = null, // how often this plant should be watered
    val manualWateringOnly: Boolean = false // true = hand-watered, shown in amber on custom maps, not part of a path
)