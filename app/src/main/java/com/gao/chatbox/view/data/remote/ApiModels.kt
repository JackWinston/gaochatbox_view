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
    @SerializedName("owned_by") val ownedBy: String = ""
)
