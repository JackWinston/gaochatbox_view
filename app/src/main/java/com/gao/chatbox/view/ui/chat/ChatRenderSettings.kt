package com.gao.chatbox.view.ui.chat

/**
 * 聊天消息渲染设置
 *
 * 控制 AI 助手消息下方的元信息显示。
 * 这些设置由用户在"设置"页面配置，通过 DataStore 持久化，
 * ChatViewModel 读取后通过 StateFlow 传递给 ChatAdapter。
 *
 * @param showCharCount 是否显示字符数
 * @param showTokenCount 是否显示 token 数量
 * @param showModelName 是否显示模型名称
 * @param showTimestamp 是否显示消息时间戳
 */
data class ChatRenderSettings(
    val showCharCount: Boolean = false,
    val showTokenCount: Boolean = false,
    val showModelName: Boolean = false,
    val showTimestamp: Boolean = false
)
