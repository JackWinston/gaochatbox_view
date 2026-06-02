package com.gao.chatbox.view.util

import com.gao.chatbox.view.data.model.SystemPrompt
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptManager @Inject constructor(
    private val mmkv: MMKV
) {

    private val gson = Gson()
    private val listType = object : TypeToken<List<SystemPrompt>>() {}.type

    fun init() {
        if (!mmkv.decodeBool(KEY_INITIALIZED, false)) {
            initDefault()
        }
    }

    private fun initDefault() {
        val defaultPrompt = SystemPrompt(
            content = "你是一个智能助手",
            tag = "默认",
            isDefault = true
        )
        mmkv.encode(KEY_PROMPTS, gson.toJson(listOf(defaultPrompt)))
        mmkv.encode(KEY_INITIALIZED, true)
    }

    fun getAll(): List<SystemPrompt> {
        val json = mmkv.decodeString(KEY_PROMPTS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, listType)
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
        mmkv.encode(KEY_PROMPTS, gson.toJson(list))
    }

    companion object {
        private const val KEY_PROMPTS = "system_prompts"
        private const val KEY_INITIALIZED = "initialized"
    }
}
