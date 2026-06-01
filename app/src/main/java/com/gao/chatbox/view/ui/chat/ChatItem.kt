package com.gao.chatbox.view.ui.chat

sealed class ChatItem {

    abstract val id: String

    data class Timestamp(
        override val id: String,
        val timeText: String,
        val timestampMillis: Long
    ) : ChatItem()

    data class SystemPrompt(
        override val id: String = "system_prompt",
        val content: String,
        val tag: String,
        val isExpanded: Boolean = false
    ) : ChatItem()

    data class UserMessage(
        override val id: String,
        val content: String,
        val attachmentName: String? = null,
        val imageUri: String? = null
    ) : ChatItem()

    data class AssistantMessage(
        override val id: String,
        val content: String,
        val modelName: String? = null,
        val tokenCount: Int = 0,
        val createdAt: Long = 0L
    ) : ChatItem()

    data class StreamingMessage(
        override val id: String = "streaming",
        val content: String = "",
        val isThinking: Boolean = true,
        val thinkingStartTime: Long = 0L,
        val charCount: Int = 0
    ) : ChatItem()
}
