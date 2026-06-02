package com.gao.chatbox.view.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gao.chatbox.view.data.model.SystemPrompt
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private val gson = Gson()
    private val listType = object : TypeToken<List<SystemPrompt>>() {}.type

    suspend fun init() {
        val initialized = dataStore.data.map { prefs ->
            prefs[KEY_INITIALIZED] ?: false
        }.first()
        if (!initialized) {
            initDefault()
        }
    }

    private suspend fun initDefault() {
        val defaultPrompt = SystemPrompt(
            content = "你是一个智能助手",
            tag = "默认",
            isDefault = true
        )
        dataStore.edit { prefs ->
            prefs[KEY_PROMPTS] = gson.toJson(listOf(defaultPrompt))
            prefs[KEY_INITIALIZED] = true
        }
    }

    suspend fun getAll(): List<SystemPrompt> {
        val json = dataStore.data.map { prefs ->
            prefs[KEY_PROMPTS]
        }.first() ?: return emptyList()
        return try {
            gson.fromJson(json, listType)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getById(id: String): SystemPrompt? = getAll().find { it.id == id }

    suspend fun add(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        list.add(prompt)
        saveList(list)
    }

    suspend fun update(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == prompt.id }
        if (index != -1) {
            list[index] = prompt
            saveList(list)
        }
    }

    suspend fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    private suspend fun saveList(list: List<SystemPrompt>) {
        dataStore.edit { prefs ->
            prefs[KEY_PROMPTS] = gson.toJson(list)
        }
    }

    companion object {
        private val KEY_PROMPTS = stringPreferencesKey("system_prompts")
        private val KEY_INITIALIZED = booleanPreferencesKey("initialized")
    }
}
