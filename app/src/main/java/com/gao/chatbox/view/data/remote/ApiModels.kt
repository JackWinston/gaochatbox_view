package com.gao.chatbox.view.data.remote

import com.google.gson.annotations.SerializedName

data class ModelsResponse(
    @SerializedName("data") val data: List<ModelInfo> = emptyList(),
    @SerializedName("object") val objectType: String = ""
)

data class ModelInfo(
    @SerializedName("id") val id: String = "",
    @SerializedName("object") val objectType: String = "",
    @SerializedName("created") val created: Long = 0,
    @SerializedName("owned_by") val ownedBy: String = "",
    @SerializedName("context_length") val contextLength: Int? = null,
    @SerializedName("max_input_tokens") val maxInputTokens: Int? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class AnthropicModelsResponse(
    @SerializedName("data") val data: List<AnthropicModelInfo> = emptyList()
)

data class AnthropicModelInfo(
    @SerializedName("id") val id: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("max_input_tokens") val maxInputTokens: Int? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)
