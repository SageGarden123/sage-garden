package com.example.sagegarden

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SageChatDao {
    @Query("SELECT * FROM sage_chat_message ORDER BY timestamp ASC")
    fun getAll(): Flow<List<SageChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SageChatMessageEntity)

    @Query("DELETE FROM sage_chat_message")
    suspend fun clearAll()
}
