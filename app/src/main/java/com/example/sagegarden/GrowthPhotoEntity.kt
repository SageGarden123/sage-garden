package com.example.sagegarden

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "growth_photos", indices = [Index("plantId")])
data class GrowthPhotoEntity(
    @PrimaryKey val id: String,
    val plantId: String,
    val uri: String,
    val takenAt: Long,
    val label: String = "",
    val gardenId: String = "" // which garden this entry belongs to — blank means "not yet stamped"
)