package com.gao.chatbox.view.ui.chat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        private const val DEFAULT_MAX_TOOL_CALL_ROUNDS = 8

        private val KEY_WEB_SEARCH = booleanPreferencesKey("capability_web_search")
        private val KEY_MAX_TOOL_CALL_ROUNDS = intPreferencesKey("capability_max_tool_call_rounds")
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

    private val _pendingResponsePhase = MutableStateFlow(PendingResponsePhase.IDLE)
    val pendingResponsePhase: StateFlow<PendingResponsePhase> = _pendingResponsePhase

    private val _selectedModelName = MutableStateFlow("")
    val selectedModelName: StateFlow<String> = _selectedModelName

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    private val _maxToolCallRounds = MutableStateFlow(DEFAULT_MAX_TOOL_CALL_ROUNDS)
    val maxToolCallRounds: StateFlow<Int> = _maxToolCallRounds

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _hasPendingAttachment = MutableStateFlow(false)
    val hasPendingAttachment: StateFlow<Boolean> = _hasPendingAttachment

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
    private var hasCustomTitle: Boolean = false
    private val pendingToolCalls = mutableMapOf<Int, ToolCallBuilder>()
    private var pendingAttachment: PendingAttachment? = null
    private var pendingToolFallbackMessage: String? = null
    private var activeStreamingItemId: String? = null
    private var currentToolCallRoundCount = 0

    enum class PendingResponsePhase {
        IDLE,
        THINKING,
        EXECUTING_TOOLS
    }

    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private data class ToolExecutionOutcome(
        val toolCall: ToolCall,
        val result: String,
        val isError: Boolean
    )

    private data class PendingAttachment(
        val displayName: String,
        val imageUri: String? = null,
        val imageBase64: String? = null,
        val mediaType: String? = null,
        val fileContent: String? = null
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
                _maxToolCallRounds.value = (prefs[KEY_MAX_TOOL_CALL_ROUNDS] ?: DEFAULT_MAX_TOOL_CALL_ROUNDS).coerceAtLeast(1)
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
                                content = msg.displayContent ?: msg.content,
                                requestContent = msg.content,
                                attachmentName = msg.attachmentName,
                                imageUri = msg.imageUri
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
        val attachment = pendingAttachment
        if (text.isBlank() && attachment == null) return

        viewModelScope.launch {
            val config = modelConfigManager.getDefault()
            if (config == null) {
                showErrorMessage("没有可用的模型配置")
                return@launch
            }

            val items = _chatItems.value.toMutableList()
            val now = System.currentTimeMillis()
            val displayText = buildDisplayMessageText(text, attachment)
            val requestText = buildRequestMessageText(text, attachment)

            // Insert timestamp if needed
            val timestamp = ChatItemBuilder.buildTimestampIfNeeded(items, now)
            if (timestamp != null) {
                items.add(timestamp)
            }

            // Add the user message immediately. The assistant bubble appears only after the first token.
            items.add(
                ChatItem.UserMessage(
                    id = "msg_$now",
                    content = displayText,
                    requestContent = requestText,
                    attachmentName = attachment?.displayName,
                    imageUri = attachment?.imageUri
                )
            )
            thinkingStartTime = System.currentTimeMillis()
            _chatItems.value = items
            clearPendingAttachment()

            // Build message history
            val userMessages = items.filterIsInstance<ChatItem.UserMessage>()
            val assistantMessages = items.filterIsInstance<ChatItem.AssistantMessage>()
            val history = mutableListOf<MessageContext>()
            val pairs = minOf(userMessages.size, assistantMessages.size)
            for (i in 0 until pairs) {
                history.add(MessageContext("user", userMessages[i].requestContent))
                history.add(MessageContext("assistant", assistantMessages[i].content))
            }

            _isStreaming.value = true
            _pendingResponsePhase.value = PendingResponsePhase.THINKING
            accumulatedContent = ""
            lastUIUpdateTime = 0L
            activeStreamingItemId = null
            currentToolCallRoundCount = 0

            try {
                pendingToolCalls.clear()
                val result = chatRepository.sendMessage(
                    conversationId = conversationId,
                    userMessage = requestText,
                    displayMessage = displayText,
                    attachmentName = attachment?.displayName,
                    imageUri = attachment?.imageUri,
                    history = history,
                    config = config,
                    systemPromptTag = systemPromptTag.ifBlank { null },
                    systemPrompt = systemPromptContent.ifBlank { null },
                    conversationTitle = pendingConversationTitle(),
                    displayTag = pendingDisplayTag(),
                    imageBase64 = attachment?.imageBase64,
                    mediaType = attachment?.mediaType,
                    enableWebSearch = _webSearchEnabled.value
                )
                conversationId = result.conversationId
                currentAssistantMessageId = result.assistantMessageId

                collectStream(
                    result = result,
                    config = config,
                    systemPrompt = systemPromptContent,
                    allowToolCalls = _webSearchEnabled.value
                )
            } catch (e: Exception) {
                showErrorMessage("请求失败: ${e.message}")
                finishStreaming()
            }
        }
    }

    private suspend fun collectStream(
        result: StreamResult,
        config: ModelConfig,
        systemPrompt: String,
        allowToolCalls: Boolean
    ) {
        result.stream.collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    accumulatedContent += event.text
                    _pendingResponsePhase.value = PendingResponsePhase.IDLE
                    val now = System.currentTimeMillis()
                    if (now - lastUIUpdateTime >= 50) {
                        lastUIUpdateTime = now
                        triggerStreamingUpdate()
                    }
                }
                is StreamEvent.ToolCallDelta -> {
                    if (!allowToolCalls) {
                        showErrorMessage("当前模型响应返回了未启用的工具调用。")
                        finishStreaming()
                        return@collect
                    }
                    val builder = pendingToolCalls.getOrPut(event.index) { ToolCallBuilder() }
                    event.id?.let { builder.id = it }
                    event.functionName?.let { builder.name = it }
                    event.arguments?.let { builder.arguments.append(it) }
                }
                is StreamEvent.StreamEnd -> {
                    if (pendingToolCalls.isNotEmpty()) {
                        if (currentToolCallRoundCount >= _maxToolCallRounds.value) {
                            pendingToolCalls.clear()
                            showErrorMessage("已达到连续工具调用最大轮次（${_maxToolCallRounds.value}次），已停止继续调用工具。")
                            finishStreaming()
                            return@collect
                        }
                        currentToolCallRoundCount++
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
        if (accumulatedContent.isBlank()) return
        val items = _chatItems.value.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items[idx] = (items[idx] as ChatItem.StreamingMessage).copy(
                content = accumulatedContent,
                isThinking = false,
                charCount = accumulatedContent.length
            )
        } else {
            val streamingId = activeStreamingItemId ?: "streaming_${System.currentTimeMillis()}".also {
                activeStreamingItemId = it
            }
            items.add(
                ChatItem.StreamingMessage(
                    id = streamingId,
                    content = accumulatedContent,
                    isThinking = false,
                    thinkingStartTime = thinkingStartTime,
                    charCount = accumulatedContent.length
                )
            )
        }
        _chatItems.value = items
    }

    private suspend fun handleToolCalls(
        config: ModelConfig,
        systemPrompt: String
    ) {
        val items = _chatItems.value.toMutableList()
        val historyItems = items.filterNot { it is ChatItem.StreamingMessage }
        val streamIdx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (streamIdx >= 0) {
            items.removeAt(streamIdx)
        }
        activeStreamingItemId = null
        val assistantToolCallContent = accumulatedContent.takeIf { it.isNotBlank() }
        if (accumulatedContent.isNotBlank()) {
            items.add(
                ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = accumulatedContent,
                    modelName = _selectedModelName.value.ifEmpty { null }
                )
            )
        }

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
        _chatItems.value = items
        _pendingResponsePhase.value = PendingResponsePhase.EXECUTING_TOOLS

        // Save assistant message with tool_calls to database
        val toolCallsJson = Gson().toJson(toolCalls.map {
            mapOf("id" to it.id, "type" to it.type, "function" to mapOf("name" to it.function.name, "arguments" to it.function.arguments))
        })
        dbManager.addToolCallMessage(
            conversationId = conversationId,
            assistantContent = assistantToolCallContent.orEmpty(),
            toolCallsJson = toolCallsJson,
            modelName = _selectedModelName.value.ifEmpty { null }
        )

        // Execute all requested tools concurrently, then return every result to the model.
        val executionOutcomes = coroutineScope {
            toolCalls.map { toolCall ->
                async(Dispatchers.IO) {
                    val result = runCatching { executeTool(toolCall) }
                        .getOrElse { "工具执行失败: ${it.message ?: "未知错误"}" }
                    ToolExecutionOutcome(
                        toolCall = toolCall,
                        result = result,
                        isError = isToolExecutionError(result)
                    )
                }
            }.awaitAll()
        }

        val toolResults = linkedMapOf<String, String>()
        val toolExecutionSummaries = mutableListOf<String>()
        executionOutcomes.forEach { outcome ->
            toolResults[outcome.toolCall.id] = outcome.result
            toolExecutionSummaries.add("${outcome.toolCall.function.name}: ${outcome.result}")

            dbManager.addToolResultMessage(
                conversationId = conversationId,
                toolCallId = outcome.toolCall.id,
                content = outcome.result
            )

            val currentItems = _chatItems.value.toMutableList()
            val idx = currentItems.indexOfFirst {
                it is ChatItem.ToolCallMessage && it.id == "tool_${outcome.toolCall.id}"
            }
            if (idx >= 0) {
                currentItems[idx] = ChatItem.ToolCallMessage(
                    id = "tool_${outcome.toolCall.id}",
                    toolName = outcome.toolCall.function.name,
                    arguments = outcome.toolCall.function.arguments,
                    result = outcome.result,
                    status = if (outcome.isError) ChatItem.ToolCallStatus.ERROR else ChatItem.ToolCallStatus.COMPLETED
                )
                _chatItems.value = currentItems
            }
        }
        pendingToolFallbackMessage = buildToolFallbackMessage(toolExecutionSummaries)

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
        activeStreamingItemId = null
        thinkingStartTime = System.currentTimeMillis()
        _pendingResponsePhase.value = PendingResponsePhase.THINKING

        // Send tool results and continue streaming
        try {
            val secondResult = chatRepository.sendToolResult(
                conversationId = conversationId,
                history = buildOpenAiMessageHistory(historyItems),
                assistantContent = assistantToolCallContent,
                toolCalls = toolCalls,
                toolResults = toolResults,
                config = config,
                enableWebSearch = _webSearchEnabled.value,
                assistantMessageId = currentAssistantMessageId
            )

            collectStream(
                result = secondResult,
                config = config,
                systemPrompt = systemPrompt,
                allowToolCalls = _webSearchEnabled.value
            )
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
                    val input = args?.get("input")?.toString() ?: args?.get("query")?.toString().orEmpty()
                    if (input.isBlank()) "搜索关键词或 URL 为空" else WebSearchTool.execute(input)
                } catch (e: Exception) {
                    "搜索执行失败: ${e.message ?: "未知错误"}"
                }
            }
            else -> "未知工具: ${toolCall.function.name}"
        }
    }

    private fun buildOpenAiMessageHistory(items: List<ChatItem>): List<OpenAiChatMessage> {
        val messages = mutableListOf<OpenAiChatMessage>()
        if (systemPromptContent.isNotBlank()) {
            messages.add(OpenAiChatMessage(role = "system", content = systemPromptContent))
        }
        for (item in items) {
            when (item) {
                is ChatItem.UserMessage -> {
                    messages.add(OpenAiChatMessage(role = "user", content = item.requestContent))
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
        val fallbackMessage = pendingToolFallbackMessage
        if (idx >= 0) {
            if (accumulatedContent.isNotBlank()) {
                items[idx] = ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = accumulatedContent,
                    modelName = _selectedModelName.value.ifEmpty { null },
                    tokenCount = tokenCount ?: 0,
                    createdAt = System.currentTimeMillis()
                )
            } else if (!fallbackMessage.isNullOrBlank()) {
                items[idx] = ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = fallbackMessage,
                    modelName = _selectedModelName.value.ifEmpty { null },
                    tokenCount = tokenCount ?: 0,
                    createdAt = System.currentTimeMillis()
                )
            } else {
                items.removeAt(idx)
            }
            _chatItems.value = items
        } else if (accumulatedContent.isNotBlank() || !fallbackMessage.isNullOrBlank()) {
            items.add(
                ChatItem.AssistantMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    content = accumulatedContent.ifBlank { fallbackMessage.orEmpty() },
                    modelName = _selectedModelName.value.ifEmpty { null },
                    tokenCount = tokenCount ?: 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            _chatItems.value = items
        }

        // Auto-generate title after first user message
        val userMessageCount = items.count { it is ChatItem.UserMessage }
        if (userMessageCount == 1 && !titleGenerated) {
            generateTitle(items)
        }

        _isStreaming.value = false
        accumulatedContent = ""
        pendingToolFallbackMessage = null
        streamingJob = null
        activeStreamingItemId = null
        currentToolCallRoundCount = 0
        _pendingResponsePhase.value = PendingResponsePhase.IDLE
    }

    private fun isToolExecutionError(result: String): Boolean {
        return result.startsWith("搜索失败") ||
            result.startsWith("搜索执行失败") ||
            result.startsWith("未知工具") ||
            result.startsWith("搜索关键词为空") ||
            result.startsWith("搜索关键词或 URL 为空")
    }

    private fun buildToolFallbackMessage(toolExecutionSummaries: List<String>): String {
        if (toolExecutionSummaries.isEmpty()) return ""
        return buildString {
            appendLine("工具调用已完成，但模型没有返回最终总结。以下是工具结果：")
            appendLine()
            toolExecutionSummaries.forEachIndexed { index, summary ->
                appendLine("${index + 1}. $summary")
            }
        }.trim()
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
        activeStreamingItemId = null
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
        titleGenerated = true
        hasCustomTitle = true
        viewModelScope.launch {
            if (conversationId > 0L) {
                chatRepository.updateConversationTitle(conversationId, newTitle, newTitle)
            }
        }
    }

    fun deleteConversation() {
        if (conversationId <= 0L) return
        viewModelScope.launch {
            dbManager.deleteConversation(conversationId)
        }
    }

    suspend fun getModelConfigs(): List<ModelConfig> = modelConfigManager.getAll()

    fun attachImage(
        displayName: String,
        imageUri: String,
        imageBase64: String,
        mediaType: String?
    ) {
        pendingAttachment = PendingAttachment(
            displayName = displayName,
            imageUri = imageUri,
            imageBase64 = imageBase64,
            mediaType = mediaType
        )
        _hasPendingAttachment.value = true
    }

    fun attachTextFile(displayName: String, fileContent: String) {
        pendingAttachment = PendingAttachment(
            displayName = displayName,
            fileContent = fileContent
        )
        _hasPendingAttachment.value = true
    }

    fun clearPendingAttachment() {
        pendingAttachment = null
        _hasPendingAttachment.value = false
    }

    // endregion

    private fun buildDisplayMessageText(text: String, attachment: PendingAttachment?): String {
        if (!text.isBlank()) return text
        return if (attachment != null) "请查看附件内容。"
        else text
    }

    private fun buildRequestMessageText(text: String, attachment: PendingAttachment?): String {
        if (attachment?.fileContent != null) {
            val prompt = text.ifBlank { "请结合附件文件内容进行处理。" }
            return buildString {
                append(prompt)
                append("\n\n[附件文件: ")
                append(attachment.displayName)
                append("]\n")
                append(attachment.fileContent)
            }
        }
        return if (!text.isBlank()) text else "请查看附件内容。"
    }

    private fun pendingConversationTitle(): String? {
        return if (conversationId == 0L && hasCustomTitle) _title.value.takeIf { it.isNotBlank() } else null
    }

    private fun pendingDisplayTag(): String? {
        return if (conversationId == 0L) _title.value.takeIf { it.isNotBlank() } else null
    }

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
