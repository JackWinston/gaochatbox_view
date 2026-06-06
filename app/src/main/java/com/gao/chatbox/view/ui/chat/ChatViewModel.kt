package com.gao.chatbox.view.ui.chat
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.remote.OpenAiChatMessage
import com.gao.chatbox.view.data.remote.StreamEvent
import com.gao.chatbox.view.data.remote.ToolCall
import com.gao.chatbox.view.data.remote.ToolCallFunction
import com.gao.chatbox.view.data.repository.ChatRepository
import com.gao.chatbox.view.data.repository.StreamResult
import com.gao.chatbox.view.util.DebugLogManager
import com.gao.chatbox.view.util.ModelConfigManager
import com.gao.chatbox.view.util.ModelContextLimitResolver
import com.gao.chatbox.view.util.WebSearchTool
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
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

/**
 * 聊天页面的 ViewModel
 *
 * 核心职责：
 * - 管理聊天消息列表（ChatItem）的状态
 * - 处理消息发送和流式响应接收
 * - 管理工具调用（网页搜索）的执行和结果回传
 * - 上下文压缩：自动管理历史消息以适应模型上下文窗口
 * - 自动为新对话生成标题
 * - 管理附件（图片/文件）的挂载状态
 * - 模型选择和配置管理
 *
 * 数据流：
 * - 发送：用户输入 → ViewModel → ChatRepository → API → 流式响应
 * - 接收：StreamEvent → ViewModel 更新 chatItems → Activity 观察并渲染
 * - 持久化：消息通过 ChatDatabaseManager 保存到 Room 数据库
 */
class ChatViewModel(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val dbManager: ChatDatabaseManager,
    private val modelConfigManager: ModelConfigManager,
    private val modelContextLimitResolver: ModelContextLimitResolver,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private const val DEFAULT_MAX_TOOL_CALL_ROUNDS = 8

        // DataStore 偏好键
        private val KEY_WEB_SEARCH = booleanPreferencesKey("capability_web_search")
        private val KEY_MAX_TOOL_CALL_ROUNDS = intPreferencesKey("capability_max_tool_call_rounds")
        private val KEY_SHOW_CHAR_COUNT = booleanPreferencesKey("ui_show_char_count")
        private val KEY_SHOW_TOKEN_COUNT = booleanPreferencesKey("ui_show_token_count")
        private val KEY_SHOW_MODEL_NAME = booleanPreferencesKey("ui_show_model_name")
        private val KEY_SHOW_TIMESTAMP = booleanPreferencesKey("ui_show_timestamp")
    }

    // ==================== UI 状态 StateFlow ====================

    /** 聊天消息列表，Activity 通过 collect 观察变化并渲染 */
    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems

    /** 是否正在流式传输中 */
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    /** 等待响应的阶段：IDLE / THINKING / EXECUTING_TOOLS */
    private val _pendingResponsePhase = MutableStateFlow(PendingResponsePhase.IDLE)
    val pendingResponsePhase: StateFlow<PendingResponsePhase> = _pendingResponsePhase

    /** 当前选择的模型名称 */
    private val _selectedModelName = MutableStateFlow("")
    val selectedModelName: StateFlow<String> = _selectedModelName

    /** 网页搜索功能开关 */
    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    /** 最大工具调用轮次 */
    private val _maxToolCallRounds = MutableStateFlow(DEFAULT_MAX_TOOL_CALL_ROUNDS)
    val maxToolCallRounds: StateFlow<Int> = _maxToolCallRounds

    /** 对话标题 */
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    /** 是否有待发送的附件 */
    private val _hasPendingAttachment = MutableStateFlow(false)
    val hasPendingAttachment: StateFlow<Boolean> = _hasPendingAttachment

    /** 上下文压缩提示信息 */
    private val _contextCompressionHint = MutableStateFlow<String?>(null)
    val contextCompressionHint: StateFlow<String?> = _contextCompressionHint

    /**
     * 上下文使用信息
     * @param currentTokens 当前已使用的 token 数
     * @param contextLimit 模型的上下文限制
     * @param percent 使用百分比（0-100）
     */
    data class ContextUsageInfo(
        val currentTokens: Int = 0,
        val contextLimit: Int = 0,
        val percent: Int = 0
    )

    private val _contextUsage = MutableStateFlow(ContextUsageInfo())
    val contextUsage: StateFlow<ContextUsageInfo> = _contextUsage

    /** 渲染设置（字符数/Token数/模型名/时间戳显示开关） */
    private val _renderSettings = MutableStateFlow(ChatRenderSettings())
    val renderSettings: StateFlow<ChatRenderSettings> = _renderSettings

    // ==================== 内部状态 ====================

    /** 对话 ID（0 表示新对话，>0 表示已有对话） */
    var conversationId: Long = 0L
        private set
    private var systemPromptContent: String = ""
    private var systemPromptTag: String = ""
    /** 当前正在写入的助手消息数据库 ID */
    private var currentAssistantMessageId: Long = 0L
    /** 流式传输中累积的内容文本 */
    private var accumulatedContent: String = ""
    /** 流式传输的协程 Job，用于取消 */
    private var streamingJob: Job? = null
    /** 上次 UI 更新时间戳（节流用，50ms 间隔） */
    private var lastUIUpdateTime: Long = 0L
    /** 思考开始时间（用于显示等待时长） */
    private var thinkingStartTime: Long = 0L
    /** 标题是否已自动生成 */
    private var titleGenerated: Boolean = false
    /** 用户是否手动设置了标题 */
    private var hasCustomTitle: Boolean = false
    /** 待处理的工具调用（按 index 累积参数） */
    private val pendingToolCalls = mutableMapOf<Int, ToolCallBuilder>()
    /** 待发送的附件 */
    private var pendingAttachment: PendingAttachment? = null
    /** 工具调用完成但模型无总结时的兜底消息 */
    private var pendingToolFallbackMessage: String? = null
    /** 当前流式消息的 Item ID */
    private var activeStreamingItemId: String? = null
    /** 当前工具调用轮次计数 */
    private var currentToolCallRoundCount = 0
    /** 上下文压缩规划器 */
    private val contextCompressionPlanner = ContextCompressionPlanner()

    /** 等待响应阶段枚举 */
    enum class PendingResponsePhase {
        IDLE,              // 空闲
        THINKING,          // 等待模型响应（思考中）
        EXECUTING_TOOLS,   // 正在执行工具调用
        DIRECT_ANSWER_FALLBACK // 达到工具轮次上限后，基于现有结果直接回答
    }

    /** 工具调用构建器（流式接收参数时使用） */
    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    /** 工具执行结果 */
    private data class ToolExecutionOutcome(
        val toolCall: ToolCall,
        val result: String,
        val isError: Boolean
    )

    /** 工具续轮上下文，用于超轮次时降级为直接回答 */
    private data class ToolFollowUpContext(
        val history: List<OpenAiChatMessage>,
        val assistantContent: String?,
        val toolCalls: List<ToolCall>,
        val toolResults: Map<String, String>
    )

    /** 待发送附件数据 */
    private data class PendingAttachment(
        val displayName: String,
        val imageUri: String? = null,
        val imageBase64: String? = null,
        val mediaType: String? = null,
        val fileContent: String? = null
    )

    /**
     * 初始化对话
     *
     * 由 Activity 在 onCreate 时调用。根据 conversationId 判断：
     * - conversationId > 0: 加载已有对话的历史消息
     * - conversationId == 0: 构建新对话的初始 Item 列表
     *
     * 同时初始化：网页搜索开关、UI 渲染设置、默认模型选择。
     *
     * @param conversationId 对话 ID（0 表示新对话）
     * @param systemPromptContent 系统提示词内容
     * @param systemPromptTag 系统提示词标签（角色名称）
     */
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
            // 从全局设置初始化网页搜索开关
            _webSearchEnabled.value = dataStore.data.map { prefs ->
                prefs[KEY_WEB_SEARCH] ?: false
            }.first()

            // 持续监听 UI 渲染设置变化
            dataStore.data.collect { prefs ->
                _renderSettings.value = ChatRenderSettings(
                    showCharCount = prefs[KEY_SHOW_CHAR_COUNT] ?: false,
                    showTokenCount = prefs[KEY_SHOW_TOKEN_COUNT] ?: false,
                    showModelName = prefs[KEY_SHOW_MODEL_NAME] ?: false,
                    showTimestamp = prefs[KEY_SHOW_TIMESTAMP] ?: false
                )
                _maxToolCallRounds.value = (prefs[KEY_MAX_TOOL_CALL_ROUNDS] ?: DEFAULT_MAX_TOOL_CALL_ROUNDS).coerceAtLeast(1)
            }
        }

        viewModelScope.launch {
            // 初始化默认模型
            val defaultConfig = modelConfigManager.getDefault()
            _selectedModelName.value = defaultConfig?.defaultModel?.ifEmpty { defaultConfig.models.firstOrNull() } ?: ""

            modelConfigManager.init()

            // 根据 conversationId 决定加载已有对话还是新建
            if (conversationId > 0L) {
                loadExistingConversation(conversationId)
            } else {
                buildInitialItems()
            }
        }
    }

    /**
     * 构建新对话的初始 Item 列表
     *
     * 包含：初始时间戳 + 系统提示词（如果有的话）
     */
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
            refreshContextUsage()
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

    /**
     * 发送用户消息
     *
     * 完整流程：
     * 1. 校验：非流式传输中、文本非空或有附件
     * 2. 获取默认模型配置
     * 3. 上下文压缩规划：计算历史消息和附件的压缩策略
     * 4. 构建显示文本和请求文本（可能因压缩而不同）
     * 5. 插入时间戳和用户消息到列表
     * 6. 调用 ChatRepository 发送请求并收集流式响应
     * 7. 处理流式事件：内容增量、工具调用、流结束、错误
     *
     * @param text 用户输入的文本
     */
    fun sendMessage(text: String) {
        if (_isStreaming.value) return
        val attachment = pendingAttachment
        if (text.isBlank() && attachment == null) return

        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val config = modelConfigManager.getDefault()
            if (config == null) {
                showErrorMessage("没有可用的模型配置")
                return@launch
            }

            val items = _chatItems.value.toMutableList()
            val now = System.currentTimeMillis()
            val contextLimit = resolveContextLimit(config)
            val displayText = buildDisplayMessageText(text, attachment)
            // 执行上下文压缩规划
            val plannedRequest = contextCompressionPlanner.planInitialRequest(
                previousItems = items,
                text = text,
                attachmentName = attachment?.displayName,
                fileContent = attachment?.fileContent,
                systemPrompt = systemPromptContent,
                contextLimit = contextLimit,
                enableWebSearch = _webSearchEnabled.value,
                hasImageAttachment = attachment?.imageBase64 != null
            )
            val requestText = plannedRequest.userMessage
            updateContextCompressionHint(plannedRequest.report)

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
                    history = plannedRequest.history,
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
                logCompressionReport(
                    conversationId = result.conversationId,
                    type = "Context Compression (Initial)",
                    report = plannedRequest.report
                )

                collectStream(
                    result = result,
                    config = config,
                    systemPrompt = systemPromptContent,
                    allowToolCalls = _webSearchEnabled.value
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showErrorMessage("请求失败: ${e.message}")
                finishStreaming()
            }
        }
    }

    /**
     * 收集流式响应事件
     *
     * 处理四种事件类型：
     * - ContentDelta: 内容增量 → 累积内容并节流更新 UI（50ms 间隔）
     * - ToolCallDelta: 工具调用参数增量 → 累积到 pendingToolCalls
     * - StreamEnd: 流结束 → 如果有待处理工具调用则执行，否则完成
     * - Error: 错误 → 显示错误消息并结束
     *
     * @param result 流式响应结果
     * @param config 当前模型配置
     * @param systemPrompt 系统提示词
     * @param allowToolCalls 是否允许工具调用
     */
    private suspend fun collectStream(
        result: StreamResult,
        config: ModelConfig,
        systemPrompt: String,
        allowToolCalls: Boolean,
        toolFollowUpContext: ToolFollowUpContext? = null,
        ignoreDisallowedToolCalls: Boolean = false
    ) {
        result.stream.collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    accumulatedContent += event.text
                    _pendingResponsePhase.value = PendingResponsePhase.IDLE
                    // 节流：50ms 间隔更新 UI，避免过于频繁的列表刷新
                    val now = System.currentTimeMillis()
                    if (now - lastUIUpdateTime >= 50) {
                        lastUIUpdateTime = now
                        triggerStreamingUpdate()
                    }
                }
                is StreamEvent.ToolCallDelta -> {
                    if (!allowToolCalls) {
                        if (!ignoreDisallowedToolCalls) {
                            showErrorMessage("当前模型响应返回了未启用的工具调用。")
                            finishStreaming()
                        }
                        return@collect
                    }
                    // 按 index 累积工具调用参数（流式传输中参数分片到达）
                    val builder = pendingToolCalls.getOrPut(event.index) { ToolCallBuilder() }
                    event.id?.let { builder.id = it }
                    event.functionName?.let { builder.name = it }
                    event.arguments?.let { builder.arguments.append(it) }
                }
                is StreamEvent.StreamEnd -> {
                    // 过滤掉函数名为空的无效工具调用
                    pendingToolCalls.entries.removeIf { it.value.name.isBlank() }
                    if (pendingToolCalls.isNotEmpty()) {
                        // 检查工具调用轮次限制
                        if (currentToolCallRoundCount >= _maxToolCallRounds.value) {
                            pendingToolCalls.clear()
                            if (toolFollowUpContext != null) {
                                requestDirectAnswerAfterToolLimit(config, systemPrompt, toolFollowUpContext)
                            } else {
                                showErrorMessage("已达到连续工具调用最大轮次（${_maxToolCallRounds.value}次），已停止继续调用工具。")
                                finishStreaming()
                            }
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
            val plannedToolRequest = contextCompressionPlanner.planToolFollowUp(
                historyItems = historyItems,
                systemPrompt = systemPromptContent,
                contextLimit = resolveContextLimit(config),
                enableWebSearch = _webSearchEnabled.value,
                reservedTexts = buildReservedToolTexts(
                    assistantToolCallContent = assistantToolCallContent,
                    toolCalls = toolCalls,
                    toolResults = toolResults
                )
            )
            updateContextCompressionHint(plannedToolRequest.report)
            logCompressionReport(
                conversationId = conversationId,
                type = "Context Compression (Tool Follow-up)",
                report = plannedToolRequest.report
            )
            val toolFollowUpContext = ToolFollowUpContext(
                history = plannedToolRequest.history,
                assistantContent = assistantToolCallContent,
                toolCalls = toolCalls,
                toolResults = toolResults
            )
            val secondResult = chatRepository.sendToolResult(
                conversationId = conversationId,
                history = plannedToolRequest.history,
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
                allowToolCalls = _webSearchEnabled.value,
                toolFollowUpContext = toolFollowUpContext
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showErrorMessage("工具结果请求失败: ${e.message}")
            finishStreaming()
        }
    }

    private suspend fun requestDirectAnswerAfterToolLimit(
        config: ModelConfig,
        systemPrompt: String,
        toolFollowUpContext: ToolFollowUpContext
    ) {
        discardStreamingMessage()
        accumulatedContent = ""
        lastUIUpdateTime = 0L
        pendingToolCalls.clear()
        activeStreamingItemId = null
        _pendingResponsePhase.value = PendingResponsePhase.DIRECT_ANSWER_FALLBACK

        try {
            val degradedResult = chatRepository.sendToolResult(
                conversationId = conversationId,
                history = toolFollowUpContext.history,
                assistantContent = toolFollowUpContext.assistantContent,
                toolCalls = toolFollowUpContext.toolCalls,
                toolResults = toolFollowUpContext.toolResults,
                config = config,
                enableWebSearch = false,
                assistantMessageId = currentAssistantMessageId,
                directAnswerInstruction = context.getString(R.string.tool_limit_direct_answer_prompt)
            )
            collectStream(
                result = degradedResult,
                config = config,
                systemPrompt = systemPrompt,
                allowToolCalls = false,
                ignoreDisallowedToolCalls = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showErrorMessage("达到工具轮次上限后，直接回答请求失败: ${e.message}")
            finishStreaming()
        }
    }

    private fun buildReservedToolTexts(
        assistantToolCallContent: String?,
        toolCalls: List<ToolCall>,
        toolResults: Map<String, String>
    ): List<String> {
        val reservedTexts = mutableListOf<String>()
        assistantToolCallContent?.takeIf { it.isNotBlank() }?.let { reservedTexts += it }
        toolCalls.forEach { toolCall ->
            reservedTexts += buildString {
                append(toolCall.function.name)
                append('\n')
                append(toolCall.function.arguments)
                toolResults[toolCall.id]?.takeIf { it.isNotBlank() }?.let { result ->
                    append('\n')
                    append(result)
                }
            }
        }
        return reservedTexts
    }

    private fun executeTool(toolCall: ToolCall): String {
        return when (toolCall.function.name) {
            "search_web" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val args = Gson().fromJson(toolCall.function.arguments, Map::class.java) as? Map<String, Any>
                    val query = args?.get("query")?.toString().orEmpty()
                    if (query.isBlank()) "搜索关键词为空" else WebSearchTool.searchQuery(query)
                } catch (e: Exception) {
                    "搜索执行失败: ${e.message ?: "未知错误"}"
                }
            }
            "fetch_webpage" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val args = Gson().fromJson(toolCall.function.arguments, Map::class.java) as? Map<String, Any>
                    val url = args?.get("url")?.toString().orEmpty()
                    if (url.isBlank()) "URL 为空" else WebSearchTool.fetchContent(url)
                } catch (e: Exception) {
                    "网页内容获取失败: ${e.message ?: "未知错误"}"
                }
            }
            else -> "未知工具: ${toolCall.function.name}"
        }
    }

    private suspend fun resolveContextLimit(config: ModelConfig): Int {
        val modelName = _selectedModelName.value.ifEmpty { config.defaultModel }
        return modelContextLimitResolver.resolve(config, modelName)
    }

    fun refreshContextUsage() {
        viewModelScope.launch {
            if (conversationId <= 0L) {
                _contextUsage.value = ContextUsageInfo()
                return@launch
            }
            val config = modelConfigManager.getDefault()
            val contextLimit = if (config != null) {
                resolveContextLimit(config)
            } else {
                ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT
            }
            val totalTokens = dbManager.getTotalTokens(conversationId)
            val percent = if (contextLimit > 0) ((totalTokens.toFloat() / contextLimit) * 100).toInt().coerceIn(0, 100) else 0
            _contextUsage.value = ContextUsageInfo(
                currentTokens = totalTokens,
                contextLimit = contextLimit,
                percent = percent
            )
        }
    }

    private fun logCompressionReport(
        conversationId: Long,
        type: String,
        report: ContextCompressionPlanner.CompressionReport
    ) {
        DebugLogManager.appendLog(
            context = context,
            conversationId = conversationId,
            type = type,
            url = "local://context-compression",
            requestBody = Gson().toJson(report),
            responseBody = null
        )
    }

    private fun updateContextCompressionHint(
        report: ContextCompressionPlanner.CompressionReport
    ) {
        val attachmentCompressed = report.attachmentStrategy == "excerpt"
        val hasHistoryCompression = report.summarizedHistoryMessages > 0
        _contextCompressionHint.value = when {
            hasHistoryCompression && attachmentCompressed -> {
                context.getString(
                    R.string.chat_context_compression_with_attachment,
                    report.keptHistoryMessages,
                    report.summarizedHistoryMessages
                )
            }
            hasHistoryCompression -> {
                context.getString(
                    R.string.chat_context_compression_history_only,
                    report.keptHistoryMessages,
                    report.summarizedHistoryMessages
                )
            }
            attachmentCompressed -> {
                context.getString(R.string.chat_context_compression_attachment_only)
            }
            else -> null
        }
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
        pendingToolCalls.clear()
        pendingToolFallbackMessage = null
        streamingJob = null
        activeStreamingItemId = null
        currentToolCallRoundCount = 0
        _pendingResponsePhase.value = PendingResponsePhase.IDLE
        refreshContextUsage()
    }

    private fun isToolExecutionError(result: String): Boolean {
        return result.startsWith("搜索失败") ||
            result.startsWith("搜索执行失败") ||
            result.startsWith("网页内容获取失败") ||
            result.startsWith("未知工具") ||
            result.startsWith("搜索关键词为空") ||
            result.startsWith("URL 为空")
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

    private fun discardStreamingMessage() {
        val items = _chatItems.value.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items.removeAt(idx)
            _chatItems.value = items
        }
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
            val shouldKeepManualContext = config.contextLimitManuallySet != false
            val resolvedContextLimit = if (shouldKeepManualContext) {
                config.contextLimit
            } else {
                modelContextLimitResolver.resolve(config, modelName)
            }
            val updated = config.copy(
                isDefault = true,
                defaultModel = modelName,
                contextLimit = resolvedContextLimit,
                detectedContextLimit = if (shouldKeepManualContext) config.detectedContextLimit else resolvedContextLimit,
                contextLimitManuallySet = if (shouldKeepManualContext) config.contextLimitManuallySet else false
            )
            modelConfigManager.update(updated)
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
            DebugLogManager.deleteLogFile(context, conversationId)
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

    private fun pendingConversationTitle(): String? {
        return if (conversationId == 0L && hasCustomTitle) _title.value.takeIf { it.isNotBlank() } else null
    }

    override fun onCleared() {
        streamingJob?.cancel()
        super.onCleared()
    }

    private fun pendingDisplayTag(): String? {
        return if (conversationId == 0L) _title.value.takeIf { it.isNotBlank() } else null
    }

    class Factory @Inject constructor(
        private val context: Context,
        private val chatRepository: ChatRepository,
        private val dbManager: ChatDatabaseManager,
        private val modelConfigManager: ModelConfigManager,
        private val modelContextLimitResolver: ModelContextLimitResolver,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                context,
                chatRepository,
                dbManager,
                modelConfigManager,
                modelContextLimitResolver,
                dataStore
            ) as T
        }
    }
}
