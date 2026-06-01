package com.gao.chatbox.view.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gao.chatbox.view.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getByConversation(convId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(convId: Long, limit: Int): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: Long)

    @Query("UPDATE messages SET isStreaming = :streaming WHERE id = :id")
    suspend fun updateStreamingStatus(id: Long, streaming: Boolean)

    @Query("UPDATE messages SET content = :content, tokenCount = :tokenCount, isStreaming = :streaming WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, tokenCount: Int, streaming: Boolean)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId")
    suspend fun countByConversation(convId: Long): Int

    @Query("SELECT COALESCE(SUM(tokenCount), 0) FROM messages WHERE conversationId = :convId")
    suspend fun totalTokensByConversation(convId: Long): Int
}
