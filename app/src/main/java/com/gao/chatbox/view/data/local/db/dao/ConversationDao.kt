package com.gao.chatbox.view.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gao.chatbox.view.data.local.db.entity.ConversationEntity
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT c.*, m.content AS lastMessage, m.createdAt AS lastMessageTime
        FROM conversations c
        LEFT JOIN (
            SELECT conversationId, content, createdAt
            FROM messages
            WHERE id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)
        ) m ON c.id = m.conversationId
        ORDER BY c.updatedAt DESC
    """)
    fun getAllWithLastMessage(): Flow<List<ConversationWithLastMessage>>

    @Query("""
        SELECT c.*, m.content AS lastMessage, m.createdAt AS lastMessageTime
        FROM conversations c
        LEFT JOIN (
            SELECT conversationId, content, createdAt
            FROM messages
            WHERE id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)
        ) m ON c.id = m.conversationId
        WHERE c.title LIKE '%' || :keyword || '%' OR c.displayTag LIKE '%' || :keyword || '%'
        ORDER BY c.updatedAt DESC
    """)
    fun searchWithLastMessage(keyword: String): Flow<List<ConversationWithLastMessage>>

    @Query("""
        SELECT c.*, m.content AS lastMessage, m.createdAt AS lastMessageTime
        FROM conversations c
        LEFT JOIN (
            SELECT conversationId, content, createdAt
            FROM messages
            WHERE id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)
        ) m ON c.id = m.conversationId
        WHERE c.systemPromptTag = :tag
        ORDER BY c.updatedAt DESC
    """)
    fun getByTagWithLastMessage(tag: String): Flow<List<ConversationWithLastMessage>>

    @Query("SELECT DISTINCT systemPromptTag FROM conversations WHERE systemPromptTag IS NOT NULL AND systemPromptTag != ''")
    fun getDistinctTags(): Flow<List<String>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getByIdOnce(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :keyword || '%' ORDER BY updatedAt DESC")
    fun search(keyword: String): Flow<List<ConversationEntity>>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET totalTokenCount = (SELECT COALESCE(SUM(tokenCount), 0) FROM messages WHERE conversationId = :convId) WHERE id = :convId")
    suspend fun recalcTokenCount(convId: Long)

    @Query("UPDATE conversations SET title = :title, displayTag = :displayTag, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, displayTag: String? = null, timestamp: Long = System.currentTimeMillis())
}
