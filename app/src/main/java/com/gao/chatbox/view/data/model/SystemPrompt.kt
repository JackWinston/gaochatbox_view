package com.gao.chatbox.view.data.model

import java.util.UUID

data class SystemPrompt(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val tag: String,
    val isDefault: Boolean = false,
    val isPreset: Boolean = false,
    val presetKey: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
