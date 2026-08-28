package com.example.sagegarden

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sun_zones")
data class SunZoneEntity(
    @PrimaryKey val id: String,
    val category: String, // "full_sun" | "morning_sun" | "afternoon_sun" | "part_shade" | "full_shade"
    val pointsJson: String,
    /** "custom" = pointsJson holds 0..1 fractions over the uploaded map image; "real" = pointsJson holds [lat, lng] pairs over the real-world map. */
    val mapType: String = "custom",
    val gardenId: String = "" // blank means "not yet stamped" — filled in by SunZoneViewModel.save
)