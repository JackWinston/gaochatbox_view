package com.gao.chatbox.view.data.remote

import com.google.gson.annotations.SerializedName

data class OpenAiChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<OpenAiChatMessage>,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("stream") val stream: Boolean = true
)

data class OpenAiChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any
)

data class OpenAiStreamChunk(
    @SerializedName("id") val id: String = "",
    @SerializedName("choices") val choices: List<OpenAiStreamChoice> = emptyList()
)

data class OpenAiStreamChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("delta") val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class OpenAiStreamDelta(
    @SerializedName("role") val role: String = "",
    @SerializedName("content") val content: String? = null
)

data class OpenAiChatResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("choices") val choices: List<OpenAiResponseChoice>?
)

data class OpenAiResponseChoice(
    @SerializedName("message") val message: OpenAiChatMessage?
)
