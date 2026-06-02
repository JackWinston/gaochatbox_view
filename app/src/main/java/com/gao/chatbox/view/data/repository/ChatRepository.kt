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
import com.gao.chatbox.view.data.remote.ToolCall
import com.gao.chatbox.view.data.remote.ToolCallFunction
import com.gao.chatbox.view.data.remote.ToolDefinition
import com.gao.chatbox.view.data.remote.ToolFunctionDefinition
import com.gao.chatbox.view.util.ApiClient
import com.gao.chatbox.view.util.DebugLogManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
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
    private val context: Context,
    private val dbManager: ChatDatabaseManager
) {
    private val gson = Gson()

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
                val requestJson = gson.toJson(request)
                val responseBody = api.createMessageStream(
                    apiKey = config.apiKey,
                    request = request
                )
                wrapStreamWithLogging(
                    SseParser.parseAnthropicStream(responseBody),
                    convId,
                    "Anthropic Messages",
                    "${config.apiUrl}/messages",
                    requestJson
                )
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
                val requestJson = gson.toJson(request)
                val responseBody = api.createChatCompletionStream(
                    authorization = "Bearer ${config.apiKey}",
                    request = request
                )
                wrapStreamWithLogging(
                    SseParser.parseOpenAiStream(responseBody),
                    convId,
                    "OpenAI Chat Completions",
                    "${config.apiUrl}/chat/completions",
                    requestJson
                )
            }
        }

        return StreamResult(
            conversationId = convId,
            assistantMessageId = assistantMsgId,
            stream = stream
        )
    }

    private fun wrapStreamWithLogging(
        originalStream: Flow<StreamEvent>,
        conversationId: Long,
        type: String,
        url: String,
        requestJson: String
    ): Flow<StreamEvent> {
        val responseBuilder = StringBuilder()
        return originalStream
            .onStart {
                DebugLogManager.appendLog(context, conversationId, type, url, requestJson, null)
            }
            .onEach { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> responseBuilder.append(event.text)
                    is StreamEvent.ToolCallDelta -> {
                        responseBuilder.append("[tool_call:${event.functionName}]")
                    }
                    is StreamEvent.StreamEnd -> {
                        val responseJson = gson.toJson(mapOf(
                            "content" to responseBuilder.toString(),
                            "usage" to event.usage,
                            "finishReason" to event.finishReason
                        ))
                        DebugLogManager.appendLog(context, conversationId, "$type Response", url, null, responseJson)
                    }
                    is StreamEvent.Error -> {
                        DebugLogManager.appendLog(context, conversationId, "$type Error", url, null, event.message, isError = true)
                    }
                }
            }
    }

    suspend fun sendToolResult(
        conversationId: Long,
        history: List<OpenAiChatMessage>,
        assistantContent: String?,
        toolCalls: List<ToolCall>,
        toolResults: Map<String, String>,
        config: ModelConfig,
        enableWebSearch: Boolean,
        assistantMessageId: Long
    ): StreamResult {
        val model = config.defaultModel.ifEmpty { config.models.firstOrNull() ?: "" }
        val api = ApiClient.buildOpenAiApiStreaming(config.apiUrl)
        val tools = if (enableWebSearch) buildWebSearchTools() else null

        val messages = history.toMutableList()
        messages.add(
            OpenAiChatMessage(
                role = "assistant",
                content = assistantContent,
                toolCalls = toolCalls
            )
        )
        toolCalls.forEach { toolCall ->
            messages.add(
                OpenAiChatMessage(
                    role = "tool",
                    content = toolResults[toolCall.id] ?: "工具执行失败",
                    toolCallId = toolCall.id,
                    name = toolCall.function.name
                )
            )
        }

        val request = OpenAiChatRequest(
            model = model,
            messages = messages,
            temperature = config.temperature,
            stream = true,
            streamOptions = mapOf("include_usage" to true),
            tools = tools,
            toolChoice = null
        )

        val requestJson = gson.toJson(request)
        val responseBody = api.createChatCompletionStream(
            authorization = "Bearer ${config.apiKey}",
            request = request
        )

        return StreamResult(
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            stream = wrapStreamWithLogging(
                SseParser.parseOpenAiStream(responseBody),
                conversationId,
                "OpenAI Tool Result",
                "${config.apiUrl}/chat/completions",
                requestJson
            )
        )
    }

    private fun buildWebSearchTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                function = ToolFunctionDefinition(
                    name = "search_web",
                    description = "输入关键词时搜索互联网获取最新信息；输入 URL 时直接抓取网页内容。当需要查询实时信息、新闻、天气、网页正文等时使用",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "input" to mapOf(
                                "type" to "string",
                                "description" to "搜索关键词或完整 URL。关键词会触发搜索，URL 会直接抓取页面内容"
                            )
                        ),
                        "required" to listOf("input")
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
                    val requestJson = gson.toJson(request)
                    val response = api.createMessage(apiKey = config.apiKey, request = request)
                    val result = response.content?.firstOrNull()?.text?.trim()
                    DebugLogManager.appendLog(context, 0L, "Generate Title (Anthropic)", "${config.apiUrl}/messages", requestJson, gson.toJson(response))
                    result
                }
                else -> {
                    val api = ApiClient.buildOpenAiApi(config.apiUrl)
                    val request = OpenAiChatRequest(
                        model = model,
                        messages = listOf(OpenAiChatMessage(role = "user", content = prompt)),
                        temperature = 0.7f,
                        stream = false
                    )
                    val requestJson = gson.toJson(request)
                    val response = api.createChatCompletion(
                        authorization = "Bearer ${config.apiKey}",
                        request = request
                    )
                    val result = response.choices?.firstOrNull()?.message?.content?.toString()?.trim()
                    DebugLogManager.appendLog(context, 0L, "Generate Title (OpenAI)", "${config.apiUrl}/chat/completions", requestJson, gson.toJson(response))
                    result
                }
            }
        } catch (e: Exception) {
            DebugLogManager.appendLog(context, 0L, "Generate Title Error", config.apiUrl, null, e.message, isError = true)
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
