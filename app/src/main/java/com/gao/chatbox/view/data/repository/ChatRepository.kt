package com.gao.chatbox.view.data.repository

import android.content.Context
import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.remote.AnthropicMessage
import com.gao.chatbox.view.data.remote.AnthropicMessageRequest
import com.gao.chatbox.view.data.remote.OpenAiChatMessage
import com.gao.chatbox.view.data.remote.OpenAiChatRequest
import com.gao.chatbox.view.data.remote.SseParser
import com.gao.chatbox.view.data.remote.StreamEvent
import com.gao.chatbox.view.util.ApiClient
import kotlinx.coroutines.flow.Flow

data class StreamResult(
    val conversationId: Long,
    val assistantMessageId: Long,
    val stream: Flow<StreamEvent>
)

data class MessageContext(
    val role: String,
    val content: String
)

class ChatRepository(context: Context) {

    private val dbManager = ChatDatabaseManager.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun sendMessage(
        conversationId: Long,
        userMessage: String,
        history: List<MessageContext>,
        config: ModelConfig,
        systemPrompt: String?
    ): StreamResult {
        val convId = if (conversationId == 0L) {
            dbManager.createConversation(
                title = userMessage.take(50),
                modelId = 0L
            )
        } else {
            conversationId
        }

        dbManager.addUserMessage(convId, userMessage)

        val assistantMsgId = dbManager.addAssistantMessage(
            conversationId = convId,
            content = "",
            isStreaming = true
        )

        val model = config.defaultModel.ifEmpty { config.models.firstOrNull() ?: "" }

        val stream = when (config.apiType) {
            ModelConfig.API_TYPE_ANTHROPIC -> {
                val api = ApiClient.buildAnthropicApiStreaming(config.apiUrl)
                val messages = buildAnthropicMessages(history, userMessage)
                val request = AnthropicMessageRequest(
                    model = model,
                    maxTokens = 4096,
                    system = systemPrompt,
                    messages = messages,
                    temperature = config.temperature,
                    stream = true
                )
                val responseBody = api.createMessageStream(
                    apiKey = config.apiKey,
                    request = request
                )
                SseParser.parseAnthropicStream(responseBody)
            }
            else -> {
                val api = ApiClient.buildOpenAiApiStreaming(config.apiUrl)
                val messages = buildOpenAiMessages(history, userMessage, systemPrompt)
                val request = OpenAiChatRequest(
                    model = model,
                    messages = messages,
                    temperature = config.temperature,
                    stream = true
                )
                val responseBody = api.createChatCompletionStream(
                    authorization = "Bearer ${config.apiKey}",
                    request = request
                )
                SseParser.parseOpenAiStream(responseBody)
            }
        }

        return StreamResult(
            conversationId = convId,
            assistantMessageId = assistantMsgId,
            stream = stream
        )
    }

    suspend fun updateStreamingContent(messageId: Long, partialContent: String) {
        dbManager.updateStreamingMessage(messageId, partialContent, tokenCount = 0)
    }

    suspend fun finishMessage(messageId: Long, fullContent: String) {
        dbManager.finishStreamingMessage(messageId, fullContent, tokenCount = 0)
    }

    private fun buildOpenAiMessages(
        history: List<MessageContext>,
        userMessage: String,
        systemPrompt: String?
    ): List<OpenAiChatMessage> {
        val messages = mutableListOf<OpenAiChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(OpenAiChatMessage(role = "system", content = systemPrompt))
        }
        for (msg in history) {
            messages.add(OpenAiChatMessage(role = msg.role, content = msg.content))
        }
        messages.add(OpenAiChatMessage(role = "user", content = userMessage))
        return messages
    }

    private fun buildAnthropicMessages(
        history: List<MessageContext>,
        userMessage: String
    ): List<AnthropicMessage> {
        val messages = mutableListOf<AnthropicMessage>()
        for (msg in history) {
            messages.add(AnthropicMessage(role = msg.role, content = msg.content))
        }
        messages.add(AnthropicMessage(role = "user", content = userMessage))
        return messages
    }
}
