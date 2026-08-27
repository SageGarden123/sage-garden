package com.example.sagegarden

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "location_photos", indices = [Index("location")])
data class LocationPhotoEntity(
    @PrimaryKey val id: String,
    val location: String,
    val uri: String,
    val label: String = "",
    val takenAt: Long
)
