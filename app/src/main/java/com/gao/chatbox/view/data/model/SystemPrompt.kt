package com.gao.chatbox.view.data.model

import org.json.JSONObject
import java.util.UUID

data class SystemPrompt(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val tag: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("content", content)
        put("tag", tag)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SystemPrompt = SystemPrompt(
            id = json.getString("id"),
            content = json.getString("content"),
            tag = json.getString("tag"),
            isDefault = json.getBoolean("isDefault"),
            createdAt = json.getLong("createdAt")
        )
    }
}
