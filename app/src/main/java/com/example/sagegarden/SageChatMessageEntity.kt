package com.example.sagegarden

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sage_chat_message")
data class SageChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long
)
