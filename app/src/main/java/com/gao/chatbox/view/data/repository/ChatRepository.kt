package com.gao.chatbox.view.data.repository

import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.remote.AnthropicMessage
import com.gao.chatbox.view.data.remote.AnthropicMessageRequest
import com.gao.chatbox.view.data.remote.OpenAiChatMessage
import com.gao.chatbox.view.data.remote.OpenAiChatRequest
import com.gao.chatbox.view.data.remote.SseParser
import com.gao.chatbox.view.data.remote.StreamEvent
import com.gao.chatbox.view.data.remote.ToolCall
import com.gao.chatbox.view.data.remote.ToolCallFunction
import com.gao.chatbox.view.data.remote.ToolDefinition
import com.gao.chatbox.view.data.remote.ToolFunctionDefinition
import com.gao.chatbox.view.util.ApiClient
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class StreamResult(
    val conversationId: Long,
    val assistantMessageId: Long,
    val stream: Flow<StreamEvent>
)

data class MessageContext(
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val mediaType: String? = null
)

@Singleton
class ChatRepository @Inject constructor(
    private val dbManager: ChatDatabaseManager
) {

    suspend fun sendMessage(
        conversationId: Long,
        userMessage: String,
        displayMessage: String? = null,
        attachmentName: String? = null,
        imageUri: String? = null,
        history: List<MessageContext>,
        config: ModelConfig,
        systemPromptTag: String?,
        systemPrompt: String?,
        conversationTitle: String? = null,
        displayTag: String? = null,
        imageBase64: String? = null,
        mediaType: String? = null,
        enableWebSearch: Boolean = false
    ): StreamResult {
        val convId = if (conversationId == 0L) {
            dbManager.createConversation(
                title = conversationTitle?.takeIf { it.isNotBlank() } ?: userMessage.take(50),
                modelId = 0L,
                systemPromptTag = systemPromptTag,
                systemPrompt = systemPrompt,
                displayTag = displayTag
            )
        } else {
            conversationId
        }

        dbManager.addUserMessage(
            conversationId = convId,
            content = userMessage,
            displayContent = displayMessage,
            attachmentName = attachmentName,
            imageUri = imageUri
        )

        val model = config.defaultModel.ifEmpty { config.models.firstOrNull() ?: "" }

        val assistantMsgId = dbManager.addAssistantMessage(
            conversationId = convId,
            content = "",
            modelName = model,
            isStreaming = true
        )

        val tools = if (enableWebSearch) buildWebSearchTools() else null

        val stream = when (config.apiType) {
            ModelConfig.API_TYPE_ANTHROPIC -> {
                val api = ApiClient.buildAnthropicApiStreaming(config.apiUrl)
                val messages = buildAnthropicMessages(history, userMessage, imageBase64, mediaType)
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
                val messages = buildOpenAiMessages(history, userMessage, systemPrompt, imageBase64, mediaType)
                val request = OpenAiChatRequest(
                    model = model,
                    messages = messages,
                    temperature = config.temperature,
                    stream = true,
                    streamOptions = mapOf("include_usage" to true),
                    tools = tools
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

    suspend fun sendToolResult(
        conversationId: Long,
        history: List<OpenAiChatMessage>,
        toolCalls: List<ToolCall>,
        toolResults: Map<String, String>,
        config: ModelConfig,
        systemPrompt: String?,
        assistantMessageId: Long
    ): StreamResult {
        val model = config.defaultModel.ifEmpty { config.models.firstOrNull() ?: "" }
        val api = ApiClient.buildOpenAiApiStreaming(config.apiUrl)

        val messages = history.toMutableList()

        // Add assistant message with tool_calls
        messages.add(OpenAiChatMessage(
            role = "assistant",
            content = null,
            toolCalls = toolCalls
        ))

        // Add tool results
        for (toolCall in toolCalls) {
            val result = toolResults[toolCall.id] ?: "工具执行失败"
            messages.add(OpenAiChatMessage(
                role = "tool",
                content = result,
                toolCallId = toolCall.id
            ))
        }

        val tools = buildWebSearchTools()

        val request = OpenAiChatRequest(
            model = model,
            messages = messages,
            temperature = config.temperature,
            stream = true,
            streamOptions = mapOf("include_usage" to true),
            tools = tools
        )

        val responseBody = api.createChatCompletionStream(
            authorization = "Bearer ${config.apiKey}",
            request = request
        )

        return StreamResult(
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            stream = SseParser.parseOpenAiStream(responseBody)
        )
    }

    private fun buildWebSearchTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                function = ToolFunctionDefinition(
                    name = "search_web",
                    description = "搜索互联网获取最新信息，当需要查询实时信息、新闻、天气、最新事件等时使用",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "搜索关键词"
                            )
                        ),
                        "required" to listOf("query")
                    )
                )
            )
        )
    }

    suspend fun updateStreamingContent(messageId: Long, partialContent: String) {
        dbManager.updateStreamingMessage(messageId, partialContent, tokenCount = 0)
    }

    suspend fun finishMessage(messageId: Long, fullContent: String, tokenCount: Int? = null) {
        dbManager.finishStreamingMessage(messageId, fullContent, tokenCount = tokenCount ?: 0)
    }

    suspend fun generateTitle(
        config: ModelConfig,
        userMessage: String,
        assistantMessage: String
    ): String? {
        val model = config.defaultModel.ifEmpty { config.models.firstOrNull() ?: "" }
        val prompt = "请根据以下对话内容生成一个简短的标题（不超过20个字），只输出标题内容，不要加引号或其他格式。\n\n用户：$userMessage\n助手：${assistantMessage.take(500)}"

        return try {
            when (config.apiType) {
                ModelConfig.API_TYPE_ANTHROPIC -> {
                    val api = ApiClient.buildAnthropicApi(config.apiUrl)
                    val request = AnthropicMessageRequest(
                        model = model,
                        maxTokens = 100,
                        messages = listOf(AnthropicMessage(role = "user", content = prompt)),
                        temperature = 0.7f,
                        stream = false
                    )
                    val response = api.createMessage(apiKey = config.apiKey, request = request)
                    response.content?.firstOrNull()?.text?.trim()
                }
                else -> {
                    val api = ApiClient.buildOpenAiApi(config.apiUrl)
                    val request = OpenAiChatRequest(
                        model = model,
                        messages = listOf(OpenAiChatMessage(role = "user", content = prompt)),
                        temperature = 0.7f,
                        stream = false
                    )
                    val response = api.createChatCompletion(
                        authorization = "Bearer ${config.apiKey}",
                        request = request
                    )
                    response.choices?.firstOrNull()?.message?.content?.toString()?.trim()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateConversationTitle(conversationId: Long, title: String, displayTag: String? = null) {
        dbManager.updateConversationTitle(conversationId, title, displayTag)
    }

    private fun buildOpenAiMessages(
        history: List<MessageContext>,
        userMessage: String,
        systemPrompt: String?,
        imageBase64: String?,
        mediaType: String?
    ): List<OpenAiChatMessage> {
        val messages = mutableListOf<OpenAiChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(OpenAiChatMessage(role = "system", content = systemPrompt))
        }
        for (msg in history) {
            messages.add(OpenAiChatMessage(role = msg.role, content = msg.content))
        }
        val content: Any = if (imageBase64 != null) {
            listOf(
                mapOf("type" to "text", "text" to userMessage),
                mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:${mediaType ?: "image/jpeg"};base64,$imageBase64"))
            )
        } else {
            userMessage
        }
        messages.add(OpenAiChatMessage(role = "user", content = content))
        return messages
    }

    private fun buildAnthropicMessages(
        history: List<MessageContext>,
        userMessage: String,
        imageBase64: String?,
        mediaType: String?
    ): List<AnthropicMessage> {
        val messages = mutableListOf<AnthropicMessage>()
        for (msg in history) {
            messages.add(AnthropicMessage(role = msg.role, content = msg.content))
        }
        val content: Any = if (imageBase64 != null) {
            listOf(
                mapOf("type" to "image", "source" to mapOf("type" to "base64", "media_type" to (mediaType ?: "image/jpeg"), "data" to imageBase64)),
                mapOf("type" to "text", "text" to userMessage)
            )
        } else {
            userMessage
        }
        messages.add(AnthropicMessage(role = "user", content = content))
        return messages
    }
}
