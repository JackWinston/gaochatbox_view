package com.gao.chatbox.view.util

import com.gao.chatbox.view.data.model.ModelConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelConfigManager @Inject constructor(
    private val mmkv: MMKV
) {

    private val gson = Gson()
    private val listType = object : TypeToken<List<ModelConfig>>() {}.type

    fun init() {
        if (!mmkv.decodeBool(KEY_INITIALIZED, false)) {
            mmkv.encode(KEY_INITIALIZED, true)
        }
    }

    fun getAll(): List<ModelConfig> {
        val json = mmkv.decodeString(KEY_MODELS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, listType)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): ModelConfig? = getAll().find { it.id == id }

    fun getDefault(): ModelConfig? = getAll().find { it.isDefault }

    fun add(config: ModelConfig) {
        val list = getAll().toMutableList()
        if (config.isDefault) {
            for (i in list.indices) {
                list[i] = list[i].copy(isDefault = false)
            }
        }
        list.add(config)
        saveList(list)
    }

    fun update(config: ModelConfig) {
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

    fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    private fun saveList(list: List<ModelConfig>) {
        mmkv.encode(KEY_MODELS, gson.toJson(list))
    }

    companion object {
        private const val KEY_MODELS = "model_configs"
        private const val KEY_INITIALIZED = "models_initialized"
    }
}
