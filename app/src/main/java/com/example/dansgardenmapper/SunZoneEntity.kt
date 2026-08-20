package com.example.dansgardenmapper

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sun_zones")
data class SunZoneEntity(
    @PrimaryKey val id: String,
    val category: String, // "full_sun" | "morning_sun" | "afternoon_sun" | "part_shade" | "full_shade"
    val pointsJson: String
)