package com.gao.chatbox.view.data.remote

import com.google.gson.annotations.SerializedName

data class AnthropicStreamEvent(
    @SerializedName("type") val type: String = "",
    @SerializedName("index") val index: Int = 0,
    @SerializedName("delta") val delta: AnthropicStreamDelta = AnthropicStreamDelta(),
    @SerializedName("content_block") val contentBlock: AnthropicStreamContentBlock = AnthropicStreamContentBlock(),
    @SerializedName("message") val message: AnthropicStreamMessage = AnthropicStreamMessage(),
    @SerializedName("usage") val usage: AnthropicUsage? = null
)

data class AnthropicStreamDelta(
    @SerializedName("type") val type: String = "",
    @SerializedName("text") val text: String = ""
)

data class AnthropicStreamContentBlock(
    @SerializedName("type") val type: String = "",
    @SerializedName("text") val text: String = ""
)

data class AnthropicStreamMessage(
    @SerializedName("id") val id: String = "",
    @SerializedName("model") val model: String = ""
)
