package com.gao.chatbox.view.ui.chat

/**
 * 聊天列表 Item 密封类
 *
 * 定义了聊天页面 RecyclerView 中所有可能的 Item 类型。
 * 使用密封类确保 when 表达式的穷举性，ChatAdapter 根据类型渲染不同布局。
 *
 * 类型说明：
 * - Timestamp: 时间分隔线（消息间隔超过 5 分钟时自动插入）
 * - SystemPrompt: 系统提示词卡片（可折叠/展开）
 * - UserMessage: 用户消息气泡
 * - AssistantMessage: AI 助手消息气泡（Markdown 渲染）
 * - StreamingMessage: 流式传输中的消息（显示加载动画和实时内容）
 * - ToolCallMessage: 工具调用消息（如网页搜索的调用过程和结果）
 */
sealed class ChatItem {

    /** 每个 Item 的唯一标识，用于 DiffUtil 和 RecyclerView 定位 */
    abstract val id: String

    /**
     * 时间戳 Item
     * @param timeText 格式化的时间文本（如"今天 14:30"、"昨天 09:15"）
     * @param timestampMillis 原始时间戳毫秒值，用于判断是否需要插入新时间戳
     */
    data class Timestamp(
        override val id: String,
        val timeText: String,
        val timestampMillis: Long
    ) : ChatItem()

    /**
     * 系统提示词 Item
     * @param content 系统提示词全文
     * @param tag 提示词标签（角色名称）
     * @param isExpanded 是否展开显示（默认折叠，最多显示 5 行）
     */
    data class SystemPrompt(
        override val id: String = "system_prompt",
        val content: String,
        val tag: String,
        val isExpanded: Boolean = false
    ) : ChatItem()

    /**
     * 用户消息 Item
     * @param content 显示给用户的消息文本
     * @param requestContent 实际发送给 API 的消息文本（可能因上下文压缩与 content 不同）
     * @param attachmentName 附件文件名（可选）
     * @param imageUri 附件图片 URI（可选）
     */
    data class UserMessage(
        override val id: String,
        val content: String,
        val requestContent: String = content,
        val attachmentName: String? = null,
        val imageUri: String? = null
    ) : ChatItem()

    /**
     * AI 助手消息 Item
     * @param content 消息内容（支持 Markdown 渲染）
     * @param modelName 生成此消息的模型名称
     * @param tokenCount 消息的 token 数量
     * @param createdAt 消息创建时间戳
     */
    data class AssistantMessage(
        override val id: String,
        val content: String,
        val modelName: String? = null,
        val tokenCount: Int = 0,
        val createdAt: Long = 0L
    ) : ChatItem()

    /**
     * 流式传输中的消息 Item
     *
     * 在 AI 响应过程中实时显示内容。包含思考状态指示和字符计数。
     * 流式完成后会被替换为 AssistantMessage。
     *
     * @param content 当前已接收的内容文本
     * @param isThinking 是否处于思考状态（等待首个 token）
     * @param thinkingStartTime 思考开始时间，用于计算等待时长
     * @param charCount 当前内容的字符数
     */
    data class StreamingMessage(
        override val id: String = "streaming",
        val content: String = "",
        val isThinking: Boolean = true,
        val thinkingStartTime: Long = 0L,
        val charCount: Int = 0
    ) : ChatItem()

    /**
     * 工具调用消息 Item
     *
     * 显示 AI 请求调用工具（如网页搜索）的过程和结果。
     *
     * @param toolName 工具名称（如 "search_web"）
     * @param arguments 工具调用参数（JSON 字符串）
     * @param result 工具执行结果
     * @param status 调用状态：PENDING → EXECUTING → COMPLETED/ERROR
     */
    data class ToolCallMessage(
        override val id: String,
        val toolName: String,
        val arguments: String,
        val result: String = "",
        val status: ToolCallStatus = ToolCallStatus.PENDING
    ) : ChatItem()

    /** 工具调用状态枚举 */
    enum class ToolCallStatus {
        PENDING,    // 等待执行
        EXECUTING,  // 执行中
        COMPLETED,  // 执行完成
        ERROR       // 执行失败
    }
}
