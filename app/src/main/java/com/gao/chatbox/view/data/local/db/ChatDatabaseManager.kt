package com.gao.chatbox.view.data.local.db

import com.gao.chatbox.view.data.local.db.dao.ConversationDao
import com.gao.chatbox.view.data.local.db.dao.MessageDao
import com.gao.chatbox.view.data.local.db.entity.ConversationEntity
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import com.gao.chatbox.view.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatDatabaseManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {

    // ==================== Conversation ====================

    fun getAllConversations(): Flow<List<ConversationEntity>> = conversationDao.getAll()

    fun getAllConversationsWithLastMessage(): Flow<List<ConversationWithLastMessage>> =
        conversationDao.getAllWithLastMessage()

    fun searchConversationsWithLastMessage(keyword: String): Flow<List<ConversationWithLastMessage>> =
        conversationDao.searchWithLastMessage(keyword)

    fun getConversationsByTagWithLastMessage(tag: String): Flow<List<ConversationWithLastMessage>> =
        conversationDao.getByTagWithLastMessage(tag)

    fun getDistinctTags(): Flow<List<String>> = conversationDao.getDistinctTags()

    fun getConversation(id: Long): Flow<ConversationEntity?> = conversationDao.getById(id)

    fun searchConversations(keyword: String): Flow<List<ConversationEntity>> =
        conversationDao.search(keyword)

    suspend fun createConversation(
        title: String,
        modelId: Long,
        characterId: Long? = null,
        systemPromptTag: String? = null,
        systemPrompt: String? = null
    ): Long {
        val conversation = ConversationEntity(
            title = title,
            modelId = modelId,
            characterId = characterId,
            systemPromptTag = systemPromptTag,
            systemPrompt = systemPrompt,
            displayTag = systemPromptTag
        )
        return conversationDao.insert(conversation)
    }

    suspend fun updateConversation(conversation: ConversationEntity) =
        conversationDao.update(conversation)

    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)

    suspend fun updateConversationTitle(id: Long, title: String, displayTag: String? = null) =
        conversationDao.updateTitle(id, title, displayTag)

    // ==================== Message ====================

    fun getMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.getByConversation(conversationId)

    fun getRecentMessages(conversationId: Long, limit: Int = 20): Flow<List<MessageEntity>> =
        messageDao.getRecent(conversationId, limit)

    suspend fun addUserMessage(conversationId: Long, content: String, tokenCount: Int = 0): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = ROLE_USER,
            content = content,
            tokenCount = tokenCount
        )
        val id = messageDao.insert(message)
        conversationDao.updateTimestamp(conversationId)
        return id
    }

    suspend fun addAssistantMessage(
        conversationId: Long,
        content: String,
        tokenCount: Int = 0,
        modelName: String? = null,
        isStreaming: Boolean = false
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = ROLE_ASSISTANT,
            content = content,
            tokenCount = tokenCount,
            modelName = modelName,
            isStreaming = isStreaming
        )
        val id = messageDao.insert(message)
        conversationDao.updateTimestamp(conversationId)
        return id
    }

    suspend fun updateStreamingMessage(messageId: Long, content: String, tokenCount: Int) {
        messageDao.updateContent(messageId, content, tokenCount, true)
    }

    suspend fun finishStreamingMessage(messageId: Long, content: String, tokenCount: Int) {
        messageDao.updateContent(messageId, content, tokenCount, false)
    }

    suspend fun deleteMessage(messageId: Long) {
        val message = messageDao.getById(messageId) ?: return
        messageDao.delete(message)
        conversationDao.recalcTokenCount(message.conversationId)
    }

    suspend fun getMessageCount(conversationId: Long): Int =
        messageDao.countByConversation(conversationId)

    suspend fun getTotalTokens(conversationId: Long): Int =
        messageDao.totalTokensByConversation(conversationId)

    // ==================== Tool Calls ====================

    suspend fun addToolCallMessage(
        conversationId: Long,
        assistantContent: String,
        toolCallsJson: String,
        modelName: String? = null
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = ROLE_ASSISTANT,
            content = assistantContent,
            modelName = modelName,
            toolCalls = toolCallsJson
        )
        val id = messageDao.insert(message)
        conversationDao.updateTimestamp(conversationId)
        return id
    }

    suspend fun addToolResultMessage(
        conversationId: Long,
        toolCallId: String,
        content: String
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = ROLE_TOOL,
            content = content,
            toolCallId = toolCallId
        )
        val id = messageDao.insert(message)
        conversationDao.updateTimestamp(conversationId)
        return id
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
        const val ROLE_TOOL = "tool"
    }
}
