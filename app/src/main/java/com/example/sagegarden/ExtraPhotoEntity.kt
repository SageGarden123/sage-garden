package com.example.sagegarden

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "extra_photos", indices = [Index("plantId")])
data class ExtraPhotoEntity(
    @PrimaryKey val id: String,
    val plantId: String,
    val uri: String,
    val label: String = "",
    val addedAt: Long,
    val gardenId: String = "" // which garden this entry belongs to — blank means "not yet stamped"
)
