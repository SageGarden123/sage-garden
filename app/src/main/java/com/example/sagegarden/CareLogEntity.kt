package com.example.sagegarden

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "care_log", indices = [Index("plantId")])
data class CareLogEntity(
    @PrimaryKey val id: String,
    val plantId: String,
    val type: String, // "fertilise" or "prune"
    val date: Long,
    val notes: String = ""
)