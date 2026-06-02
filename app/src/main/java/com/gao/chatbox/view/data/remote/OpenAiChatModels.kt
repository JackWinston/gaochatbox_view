package com.gao.chatbox.view.data.remote

import com.google.gson.annotations.SerializedName

data class OpenAiChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<OpenAiChatMessage>,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("stream_options") val streamOptions: Map<String, Any>? = null,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = null
)

data class OpenAiChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    @SerializedName("name") val name: String? = null
)

data class OpenAiStreamChunk(
    @SerializedName("id") val id: String = "",
    @SerializedName("choices") val choices: List<OpenAiStreamChoice> = emptyList(),
    @SerializedName("usage") val usage: OpenAiUsage? = null
)

data class OpenAiUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0
)

data class OpenAiStreamChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("delta") val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class OpenAiStreamDelta(
    @SerializedName("role") val role: String = "",
    @SerializedName("content") val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<StreamToolCall>? = null
)

data class StreamToolCall(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("function") val function: StreamToolCallFunction? = null
)

data class StreamToolCallFunction(
    @SerializedName("name") val name: String? = null,
    @SerializedName("arguments") val arguments: String? = null
)

data class OpenAiChatResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("choices") val choices: List<OpenAiResponseChoice>?
)

data class OpenAiResponseChoice(
    @SerializedName("message") val message: OpenAiChatMessage?,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ToolDefinition(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: ToolFunctionDefinition
)

data class ToolFunctionDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, Any>
)

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: ToolCallFunction
)

data class ToolCallFunction(
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String
)
