package com.gao.chatbox.view.util

import android.content.Context
import android.content.SharedPreferences
import com.gao.chatbox.view.data.model.SystemPrompt
import org.json.JSONArray

object SystemPromptManager {

    private const val PREFS_NAME = "system_prompts_prefs"
    private const val KEY_PROMPTS = "system_prompts"
    private const val KEY_INITIALIZED = "initialized"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initDefault()
        }
    }

    private fun initDefault() {
        val defaultPrompt = SystemPrompt(
            content = "你是一个智能助手",
            tag = "默认",
            isDefault = true
        )
        val array = JSONArray()
        array.put(defaultPrompt.toJson())
        prefs.edit()
            .putString(KEY_PROMPTS, array.toString())
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    fun getAll(): List<SystemPrompt> {
        val json = prefs.getString(KEY_PROMPTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { SystemPrompt.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): SystemPrompt? = getAll().find { it.id == id }

    fun add(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        list.add(prompt)
        saveList(list)
    }

    fun update(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == prompt.id }
        if (index != -1) {
            list[index] = prompt
            saveList(list)
        }
    }

    fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    private fun saveList(list: List<SystemPrompt>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROMPTS, array.toString()).apply()
    }
}
