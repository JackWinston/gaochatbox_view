package com.gao.chatbox.view.data.model

import java.util.UUID

data class ModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val apiType: String = API_TYPE_OPENAI,
    val apiUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val contextLimit: Int = 4096,
    val temperature: Float = 0.7f,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val API_TYPE_OPENAI = "openai"
        const val API_TYPE_ANTHROPIC = "anthropic"
    }
}
