package com.gao.chatbox.view.ui.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.remote.OpenAiChatMessage
import com.gao.chatbox.view.data.remote.StreamEvent
import com.gao.chatbox.view.data.remote.ToolCall
import com.gao.chatbox.view.data.remote.ToolCallFunction
import com.gao.chatbox.view.data.repository.ChatRepository
import com.gao.chatbox.view.data.repository.MessageContext
import com.gao.chatbox.view.data.repository.StreamResult
import com.gao.chatbox.view.util.ModelConfigManager
import com.gao.chatbox.view.util.WebSearchTool
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val dbManager: ChatDatabaseManager,
    private val modelConfigManager: ModelConfigManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private val KEY_WEB_SEARCH = booleanPreferencesKey("capability_web_search")
        private val KEY_SHOW_CHAR_COUNT = booleanPreferencesKey("ui_show_char_count")
        private val KEY_SHOW_TOKEN_COUNT = booleanPreferencesKey("ui_show_token_count")
        private val KEY_SHOW_MODEL_NAME = booleanPreferencesKey("ui_show_model_name")
        private val KEY_SHOW_TIMESTAMP = booleanPreferencesKey("ui_show_timestamp")
    }

    // UI State
    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _selectedModelName = MutableStateFlow("")
    val selectedModelName: StateFlow<String> = _selectedModelName

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // UI Settings
    private val _showCharCount = MutableStateFlow(false)
    val showCharCount: StateFlow<Boolean> = _showCharCount

    private val _showTokenCount = MutableStateFlow(false)
    val showTokenCount: StateFlow<Boolean> = _showTokenCount

    private val _showModelName = MutableStateFlow(false)
    val showModelName: StateFlow<Boolean> = _showModelName

    private val _showTimestamp = MutableStateFlow(false)
    val showTimestamp: StateFlow<Boolean> = _showTimestamp

    // Internal state
    var conversationId: Long = 0L
        private set
    private var systemPromptContent: String = ""
    private var systemPromptTag: String = ""
    private var currentAssistantMessageId: Long = 0L
    private var accumulatedContent: String = ""
    private var streamingJob: Job? = null
    private var lastUIUpdateTime: Long = 0L
    private var thinkingStartTime: Long = 0L
    private var titleGenerated: Boolean = false
    private val pendingToolCalls = mutableMapOf<Int, ToolCallBuilder>()
    private var toolCallRoundCount = 0

    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    fun initConversation(
        conversationId: Long,
        systemPromptContent: String,
        systemPromptTag: String
    ) {
        this.conversationId = conversationId
        this.systemPromptContent = systemPromptContent
        this.systemPromptTag = systemPromptTag
        _title.value = systemPromptTag

        viewModelScope.launch {
            // Init web search from global setting
            _webSearchEnabled.value = dataStore.data.map { prefs ->
                prefs[KEY_WEB_SEARCH] ?: false
            }.first()

            // Init UI settings
            dataStore.data.collect { prefs ->
                _showCharCount.value = prefs[KEY_SHOW_CHAR_COUNT] ?: false
                _showTokenCount.value = prefs[KEY_SHOW_TOKEN_COUNT] ?: false
                _showModelName.value = prefs[KEY_SHOW_MODEL_NAME] ?: false
                _showTimestamp.value = prefs[KEY_SHOW_TIMESTAMP] ?: false
            }
        }

        viewModelScope.launch {
            // Init default model
            val defaultConfig = modelConfigManager.getDefault()
            _selectedModelName.value = defaultConfig?.defaultModel?.ifEmpty { defaultConfig.models.firstOrNull() } ?: ""

            modelConfigManager.init()

            if (conversationId > 0L) {
                loadExistingConversation(conversationId)
            } else {
                buildInitialItems()
            }
        }
    }

    private fun buildInitialItems() {
        val initialItems = mutableListOf<ChatItem>()
        initialItems.add(ChatItemBuilder.buildInitialTimestamp())
        if (systemPromptContent.isNotBlank()) {
            initialItems.add(
                ChatItem.SystemPrompt(
                    content = systemPromptContent,
                    tag = systemPromptTag
                )
            )
        }
        _chatItems.value = initialItems
    }

    fun loadExistingConversation(conversationId: Long) {
        titleGenerated = true
        viewModelScope.launch {
            val messages = dbManager.getMessages(conversationId).first()
            val items = mutableListOf<ChatItem>()
            items.add(ChatItemBuilder.buildInitialTimestamp())

            if (systemPromptContent.isNotBlank()) {
                items.add(
                    ChatItem.SystemPrompt(
                        content = systemPromptContent,
                        tag = systemPromptTag
                    )
                )
            }

            // Cache tool results by toolCallId for matching
            val toolResultMap = messages
                .filter { it.role == ChatDatabaseManager.ROLE_TOOL && it.toolCallId != null }
                .associateBy { it.toolCallId!! }

            for (msg in messages) {
                val timestamp = ChatItemBuilder.buildTimestampIfNeeded(items, msg.createdAt)
                if (timestamp != null) {
                    items.add(timestamp)
                }

                when (msg.role) {
                    ChatDatabaseManager.ROLE_USER -> {
                        items.add(
                            ChatItem.UserMessage(
                                id = "msg_${msg.id}",
                                content = msg.content
                            )
                        )
                    }
                    ChatDatabaseManager.ROLE_ASSISTANT -> {
                        if (!msg.toolCalls.isNullOrBlank()) {
                            val toolCalls = parseToolCallsJson(msg.toolCalls)
                            for (tc in toolCalls) {
                                val toolResult = toolResultMap[tc["id"]]
                                items.add(
                                    ChatItem.ToolCallMessage(
                                        id = "tool_${tc["id"]}",
                                        toolName = tc["function.name"] ?: "",
                                        arguments = tc["function.arguments"] ?: "",
                                        result = toolResult?.content ?: "",
                                        status = if (toolResult != null) ChatItem.ToolCallStatus.COMPLETED else ChatItem.ToolCallStatus.PENDING
                                    )
                                )
                            }
                        } else if (msg.content.isNotBlank()) {
                            items.add(
                                ChatItem.AssistantMessage(
                                    id = "msg_${msg.id}",
                                    content = msg.content,
                                    modelName = msg.modelName,
                                    tokenCount = msg.tokenCount,
                                    createdAt = msg.createdAt
                                )
                            )
                        }
                    }
                    ChatDatabaseManager.ROLE_TOOL -> {
                        // Tool results are shown as part of ToolCallMessage above
                    }
                }
            }

            _chatItems.value = items
        }
    }

    private fun parseToolCallsJson(json: String): List<Map<String, String>> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val list = Gson().fromJson(json, List::class.java) as? List<Map<String, Any>>
            list?.map { item ->
                val function = item["function"] as? Map<String, Any> ?: emptyMap()
                mapOf(
                    "id" to (item["id"]?.toString() ?: ""),
                    "type" to (item["type"]?.toString() ?: "function"),
                    "function.name" to (function["name"]?.toString() ?: ""),
                    "function.arguments" to (function["arguments"]?.toString() ?: "")
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // region Send

    fun sendMessage(text: String) {
        if (_isStreaming.value) return

        viewModelScope.launch {
            val config = modelConfigManager.getDefault()
            if (config == null) {
                showErrorMessage("没有可用的模型配置")
                return@launch
            }

            val items = _chatItems.value.toMutableList()
            val now = System.currentTimeMillis()

            // Insert timestamp if needed
            val timestamp = ChatItemBuilder.buildTimestampIfNeeded(items, now)
            if (timestamp != null) {
                items.add(timestamp)
            }

            // Add user message + streaming placeholder
            items.add(
                ChatItem.UserMessage(
                    id = "msg_$now",
                    content = text
                )
            )
            thinkingStartTime = System.currentTimeMillis()
            items.add(ChatItem.StreamingMessage(id = "streaming_$now", isThinking = true, thinkingStartTime = thinkingStartTime))
            _chatItems.value = items

            // Build message history
            val userMessages = items.filterIsInstance<ChatItem.UserMessage>()
            val assistantMessages = items.filterIsInstance<ChatItem.AssistantMessage>()
            val history = mutableListOf<MessageContext>()
            val pairs = minOf(userMessages.size, assistantMessages.size)
            for (i in 0 until pairs) {
                history.add(MessageContext("user", userMessages[i].content))
                history.add(MessageContext("assistant", assistantMessages[i].content))
            }

            _isStreaming.value = true
            accumulatedContent = ""
            lastUIUpdateTime = 0L
            toolCallRoundCount = 0

            try {
                pendingToolCalls.clear()
                val result = chatRepository.sendMessage(
                    conversationId = conversationId,
                    userMessage = text,
                    history = history,
                    config = config,
                    systemPromptTag = systemPromptTag.ifBlank { null },
                    systemPrompt = systemPromptContent.ifBlank { null },
                    enableWebSearch = _webSearchEnabled.value
                )
                conversationId = result.conversationId
                currentAssistantMessageId = result.assistantMessageId

                collectStream(result, config, systemPromptContent)
            } catch (e: Exception) {
                showErrorMessage("请求失败: ${e.message}")
                finishStreaming()
            }
        }
    }

    private suspend fun collectStream(
        result: StreamResult,
        config: ModelConfig,
        systemPrompt: String
    ) {
        result.stream.collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    accumulatedContent += event.text
                    val now = System.currentTimeMillis()
                    if (now - lastUIUpdateTime >= 50) {
                        lastUIUpdateTime = now
                        triggerStreamingUpdate()
                    }
                }
                is StreamEvent.ToolCallDelta -> {
                    val builder = pendingToolCalls.getOrPut(event.index) { ToolCallBuilder() }
                    event.id?.let { builder.id = it }
                    event.functionName?.let { builder.name = it }
                    event.arguments?.let { builder.arguments.append(it) }
                }
                is StreamEvent.StreamEnd -> {
                    if (pendingToolCalls.isNotEmpty() && toolCallRoundCount < 5) {
                        toolCallRoundCount++
                        handleToolCalls(config, systemPrompt)
                    } else {
                        triggerStreamingUpdate()
                        finishStreaming(event.usage?.completionTokens)
                    }
                }
                is StreamEvent.Error -> {
                    showErrorMessage(event.message)
                    finishStreaming()
                }
            }
        }
    }

    private fun triggerStreamingUpdate() {
        // Force UI update by creating a new list reference
        val items = _chatItems.value.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items[idx] = (items[idx] as ChatItem.StreamingMessage).copy(
                content = accumulatedContent
            )
        }
        _chatItems.value = items
    }

    private suspend fun handleToolCalls(
        config: ModelConfig,
        systemPrompt: String
    ) {
        val items = _chatItems.value.toMutableList()
        val streamIdx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (streamIdx >= 0) {
            items.removeAt(streamIdx)
        }
        if (accumulatedContent.isNotBlank()) {
            items.add(
                ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = accumulatedContent,
                    modelName = _selectedModelName.value.ifEmpty { null }
                )
            )
        }

        val messageHistory = buildOpenAiMessageHistory()

        val toolCalls = mutableListOf<ToolCall>()
        val toolCallMessages = mutableListOf<ChatItem.ToolCallMessage>()
        for ((index, builder) in pendingToolCalls.toSortedMap()) {
            val toolCallId = builder.id.ifEmpty { "call_${System.currentTimeMillis()}_$index" }
            val toolCall = ToolCall(
                id = toolCallId,
                function = ToolCallFunction(
                    name = builder.name,
                    arguments = builder.arguments.toString()
                )
            )
            toolCalls.add(toolCall)
            toolCallMessages.add(
                ChatItem.ToolCallMessage(
                    id = "tool_$toolCallId",
                    toolName = builder.name,
                    arguments = builder.arguments.toString(),
                    status = ChatItem.ToolCallStatus.EXECUTING
                )
            )
        }
        items.addAll(toolCallMessages)

        val now = System.currentTimeMillis()
        thinkingStartTime = now
        items.add(ChatItem.StreamingMessage(id = "streaming_$now", isThinking = true, thinkingStartTime = thinkingStartTime))
        _chatItems.value = items

        // Save assistant message with tool_calls to database
        val toolCallsJson = Gson().toJson(toolCalls.map {
            mapOf("id" to it.id, "type" to it.type, "function" to mapOf("name" to it.function.name, "arguments" to it.function.arguments))
        })
        dbManager.addToolCallMessage(
            conversationId = conversationId,
            assistantContent = "",
            toolCallsJson = toolCallsJson,
            modelName = _selectedModelName.value.ifEmpty { null }
        )

        // Execute tools and save results
        val toolResults = mutableMapOf<String, String>()
        for (toolCall in toolCalls) {
            val result = withContext(Dispatchers.IO) { executeTool(toolCall) }
            toolResults[toolCall.id] = result

            dbManager.addToolResultMessage(
                conversationId = conversationId,
                toolCallId = toolCall.id,
                content = result
            )

            // Update ToolCallMessage status
            val currentItems = _chatItems.value.toMutableList()
            val idx = currentItems.indexOfFirst {
                it is ChatItem.ToolCallMessage && it.id == "tool_${toolCall.id}"
            }
            if (idx >= 0) {
                currentItems[idx] = ChatItem.ToolCallMessage(
                    id = "tool_${toolCall.id}",
                    toolName = toolCall.function.name,
                    arguments = toolCall.function.arguments,
                    result = result,
                    status = ChatItem.ToolCallStatus.COMPLETED
                )
                _chatItems.value = currentItems
            }
        }

        // Create a NEW assistant message for the final response
        currentAssistantMessageId = dbManager.addAssistantMessage(
            conversationId = conversationId,
            content = "",
            modelName = _selectedModelName.value.ifEmpty { null },
            isStreaming = true
        )

        pendingToolCalls.clear()
        accumulatedContent = ""
        lastUIUpdateTime = 0L

        // Send tool results and continue streaming
        try {
            val secondResult = chatRepository.sendToolResult(
                conversationId = conversationId,
                history = messageHistory,
                toolCalls = toolCalls,
                toolResults = toolResults,
                config = config,
                systemPrompt = systemPrompt.ifBlank { null },
                assistantMessageId = currentAssistantMessageId
            )

            collectStream(secondResult, config, systemPrompt)
        } catch (e: Exception) {
            showErrorMessage("工具结果请求失败: ${e.message}")
            finishStreaming()
        }
    }

    private fun executeTool(toolCall: ToolCall): String {
        return when (toolCall.function.name) {
            "search_web" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val args = Gson().fromJson(toolCall.function.arguments, Map::class.java) as? Map<String, Any>
                    val query = args?.get("query")?.toString() ?: ""
                    if (query.isBlank()) "搜索关键词为空" else WebSearchTool.execute(query)
                } catch (e: Exception) {
                    "搜索执行失败: ${e.message ?: "未知错误"}"
                }
            }
            else -> "未知工具: ${toolCall.function.name}"
        }
    }

    private fun buildOpenAiMessageHistory(): List<OpenAiChatMessage> {
        val messages = mutableListOf<OpenAiChatMessage>()
        if (systemPromptContent.isNotBlank()) {
            messages.add(OpenAiChatMessage(role = "system", content = systemPromptContent))
        }
        val items = _chatItems.value
        for (item in items) {
            when (item) {
                is ChatItem.UserMessage -> {
                    messages.add(OpenAiChatMessage(role = "user", content = item.content))
                }
                is ChatItem.AssistantMessage -> {
                    messages.add(OpenAiChatMessage(role = "assistant", content = item.content))
                }
                else -> {}
            }
        }
        return messages
    }

    suspend fun finishStreaming(tokenCount: Int? = null) {
        chatRepository.finishMessage(currentAssistantMessageId, accumulatedContent, tokenCount)

        val items = _chatItems.value.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            if (accumulatedContent.isNotBlank()) {
                items[idx] = ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = accumulatedContent,
                    modelName = _selectedModelName.value.ifEmpty { null },
                    tokenCount = tokenCount ?: 0,
                    createdAt = System.currentTimeMillis()
                )
            } else {
                items.removeAt(idx)
            }
            _chatItems.value = items
        }

        // Auto-generate title after first user message
        val userMessageCount = items.count { it is ChatItem.UserMessage }
        if (userMessageCount == 1 && !titleGenerated) {
            generateTitle(items)
        }

        _isStreaming.value = false
        accumulatedContent = ""
        streamingJob = null
    }

    private fun generateTitle(items: List<ChatItem>) {
        val userMsg = items.filterIsInstance<ChatItem.UserMessage>().firstOrNull()?.content ?: return
        val assistantMsg = accumulatedContent

        viewModelScope.launch {
            val config = modelConfigManager.getDefault() ?: return@launch
            val newTitle = chatRepository.generateTitle(config, userMsg, assistantMsg)
            if (!newTitle.isNullOrBlank()) {
                titleGenerated = true
                systemPromptTag = newTitle
                _title.value = newTitle
                chatRepository.updateConversationTitle(conversationId, newTitle, newTitle)
            }
        }
    }

    private fun showErrorMessage(message: String) {
        val items = _chatItems.value.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items.removeAt(idx)
        }
        items.add(
            ChatItem.AssistantMessage(
                id = "error_${System.currentTimeMillis()}",
                content = "**Error:** $message",
                modelName = null
            )
        )
        _chatItems.value = items
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        viewModelScope.launch {
            finishStreaming()
        }
    }

    // endregion

    // region Actions

    fun toggleSystemPrompt(position: Int) {
        val items = _chatItems.value.toMutableList()
        val item = items[position] as? ChatItem.SystemPrompt ?: return
        items[position] = item.copy(isExpanded = !item.isExpanded)
        _chatItems.value = items
    }

    fun toggleWebSearch() {
        val newValue = !_webSearchEnabled.value
        _webSearchEnabled.value = newValue
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_WEB_SEARCH] = newValue
            }
        }
    }

    fun selectModel(modelName: String, config: ModelConfig) {
        _selectedModelName.value = modelName
        viewModelScope.launch {
            if (!config.isDefault) {
                modelConfigManager.update(config.copy(isDefault = true, defaultModel = modelName))
            } else if (config.defaultModel != modelName) {
                modelConfigManager.update(config.copy(defaultModel = modelName))
            }
        }
    }

    fun updateTitle(newTitle: String) {
        systemPromptTag = newTitle
        _title.value = newTitle
        viewModelScope.launch {
            chatRepository.updateConversationTitle(conversationId, newTitle, newTitle)
        }
    }

    fun deleteConversation() {
        // Just finish the activity - deletion is handled externally
    }

    suspend fun getModelConfigs(): List<ModelConfig> = modelConfigManager.getAll()

    // endregion

    class Factory @Inject constructor(
        private val chatRepository: ChatRepository,
        private val dbManager: ChatDatabaseManager,
        private val modelConfigManager: ModelConfigManager,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(chatRepository, dbManager, modelConfigManager, dataStore) as T
        }
    }
}
