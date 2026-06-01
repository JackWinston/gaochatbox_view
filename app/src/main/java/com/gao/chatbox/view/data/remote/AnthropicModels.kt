package com.gao.chatbox.view.data.remote

import com.google.gson.annotations.SerializedName

data class AnthropicMessageRequest(
    @SerializedName("model") val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("system") val system: String? = null,
    @SerializedName("messages") val messages: List<AnthropicMessage>,
    @SerializedName("temperature") val temperature: Float = 1.0f,
    @SerializedName("stream") val stream: Boolean = false
)

data class AnthropicMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class AnthropicMessageResponse(
    @SerializedName("id") val id: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("role") val role: String = "",
    @SerializedName("content") val content: List<AnthropicContent> = emptyList(),
    @SerializedName("model") val model: String = "",
    @SerializedName("stop_reason") val stopReason: String? = null,
    @SerializedName("usage") val usage: AnthropicUsage? = null
)

data class AnthropicContent(
    @SerializedName("type") val type: String = "",
    @SerializedName("text") val text: String = ""
)

data class AnthropicUsage(
    @SerializedName("input_tokens") val inputTokens: Int = 0,
    @SerializedName("output_tokens") val outputTokens: Int = 0
)
