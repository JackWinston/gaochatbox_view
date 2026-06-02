package com.gao.chatbox.view.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gao.chatbox.view.data.model.ModelConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelConfigManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private val gson = Gson()
    private val listType = object : TypeToken<List<ModelConfig>>() {}.type
    private val contextCacheType = object : TypeToken<Map<String, Int>>() {}.type

    suspend fun init() {
        val initialized = dataStore.data.map { prefs ->
            prefs[KEY_INITIALIZED] ?: false
        }.first()
        if (!initialized) {
            dataStore.edit { prefs ->
                prefs[KEY_INITIALIZED] = true
            }
        }
    }

    suspend fun getAll(): List<ModelConfig> {
        val json = dataStore.data.map { prefs ->
            prefs[KEY_MODELS]
        }.first() ?: return emptyList()
        return try {
            gson.fromJson(json, listType)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getById(id: String): ModelConfig? = getAll().find { it.id == id }

    suspend fun getDefault(): ModelConfig? = getAll().find { it.isDefault }

    suspend fun add(config: ModelConfig) {
        val list = getAll().toMutableList()
        if (config.isDefault) {
            for (i in list.indices) {
                list[i] = list[i].copy(isDefault = false)
            }
        }
        list.add(config)
        saveList(list)
    }

    suspend fun update(config: ModelConfig) {
        val list = getAll().toMutableList()
        if (config.isDefault) {
            for (i in list.indices) {
                if (list[i].id != config.id) {
                    list[i] = list[i].copy(isDefault = false)
                }
            }
        }
        val index = list.indexOfFirst { it.id == config.id }
        if (index != -1) {
            list[index] = config
            saveList(list)
        }
    }

    suspend fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    suspend fun getCachedContextLimit(cacheKey: String): Int? {
        val json = dataStore.data.map { prefs ->
            prefs[KEY_CONTEXT_LIMIT_CACHE]
        }.first() ?: return null
        return try {
            val cache = gson.fromJson<Map<String, Int>>(json, contextCacheType) ?: emptyMap()
            cache[cacheKey]
        } catch (_: Exception) {
            null
        }
    }

    suspend fun cacheContextLimit(cacheKey: String, contextLimit: Int) {
        if (cacheKey.isBlank() || contextLimit <= 0) return
        val current = getContextLimitCache().toMutableMap()
        current[cacheKey] = contextLimit
        dataStore.edit { prefs ->
            prefs[KEY_CONTEXT_LIMIT_CACHE] = gson.toJson(current)
        }
    }

    private suspend fun getContextLimitCache(): Map<String, Int> {
        val json = dataStore.data.map { prefs ->
            prefs[KEY_CONTEXT_LIMIT_CACHE]
        }.first() ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, Int>>(json, contextCacheType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun saveList(list: List<ModelConfig>) {
        dataStore.edit { prefs ->
            prefs[KEY_MODELS] = gson.toJson(list)
        }
    }

    companion object {
        private val KEY_MODELS = stringPreferencesKey("model_configs")
        private val KEY_INITIALIZED = booleanPreferencesKey("models_initialized")
        private val KEY_CONTEXT_LIMIT_CACHE = stringPreferencesKey("model_context_limit_cache")
    }
}
