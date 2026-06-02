package com.gao.chatbox.view.ui.home.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.util.ApiClient
import com.gao.chatbox.view.util.ModelConfigManager
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SettingsViewModel(
    private val modelConfigManager: ModelConfigManager,
    private val mmkv: MMKV
) : ViewModel() {

    companion object {
        private const val KEY_SHOW_CHAR_COUNT = "ui_show_char_count"
        private const val KEY_SHOW_TOKEN_COUNT = "ui_show_token_count"
        private const val KEY_SHOW_MODEL_NAME = "ui_show_model_name"
        private const val KEY_SHOW_TIMESTAMP = "ui_show_timestamp"
        private const val KEY_WEB_SEARCH = "capability_web_search"
    }

    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: StateFlow<List<ModelConfig>> = _models

    private val _showCharCount = MutableStateFlow(false)
    val showCharCount: StateFlow<Boolean> = _showCharCount

    private val _showTokenCount = MutableStateFlow(false)
    val showTokenCount: StateFlow<Boolean> = _showTokenCount

    private val _showModelName = MutableStateFlow(false)
    val showModelName: StateFlow<Boolean> = _showModelName

    private val _showTimestamp = MutableStateFlow(false)
    val showTimestamp: StateFlow<Boolean> = _showTimestamp

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    init {
        modelConfigManager.init()
        refreshModels()
        loadSettings()
    }

    fun refreshModels() {
        _models.value = modelConfigManager.getAll()
    }

    private fun loadSettings() {
        _showCharCount.value = mmkv.decodeBool(KEY_SHOW_CHAR_COUNT, false)
        _showTokenCount.value = mmkv.decodeBool(KEY_SHOW_TOKEN_COUNT, false)
        _showModelName.value = mmkv.decodeBool(KEY_SHOW_MODEL_NAME, false)
        _showTimestamp.value = mmkv.decodeBool(KEY_SHOW_TIMESTAMP, false)
        _webSearchEnabled.value = mmkv.decodeBool(KEY_WEB_SEARCH, false)
    }

    fun addModel(config: ModelConfig) {
        modelConfigManager.add(config)
        refreshModels()
    }

    fun updateModel(config: ModelConfig) {
        modelConfigManager.update(config)
        refreshModels()
    }

    fun deleteModel(id: String) {
        modelConfigManager.delete(id)
        refreshModels()
    }

    fun updateUiSetting(setting: SettingsAdapter.UiSetting, enabled: Boolean) {
        val key = when (setting) {
            SettingsAdapter.UiSetting.CHAR_COUNT -> KEY_SHOW_CHAR_COUNT
            SettingsAdapter.UiSetting.TOKEN_COUNT -> KEY_SHOW_TOKEN_COUNT
            SettingsAdapter.UiSetting.MODEL_NAME -> KEY_SHOW_MODEL_NAME
            SettingsAdapter.UiSetting.TIMESTAMP -> KEY_SHOW_TIMESTAMP
        }
        mmkv.encode(key, enabled)
        when (setting) {
            SettingsAdapter.UiSetting.CHAR_COUNT -> _showCharCount.value = enabled
            SettingsAdapter.UiSetting.TOKEN_COUNT -> _showTokenCount.value = enabled
            SettingsAdapter.UiSetting.MODEL_NAME -> _showModelName.value = enabled
            SettingsAdapter.UiSetting.TIMESTAMP -> _showTimestamp.value = enabled
        }
    }

    fun updateCapabilitySetting(setting: SettingsAdapter.CapabilitySetting, enabled: Boolean) {
        when (setting) {
            SettingsAdapter.CapabilitySetting.WEB_SEARCH -> {
                mmkv.encode(KEY_WEB_SEARCH, enabled)
                _webSearchEnabled.value = enabled
            }
        }
    }

    suspend fun fetchModels(apiUrl: String, apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            val response = ApiClient.fetchModels(apiUrl, apiKey)
            response.data.map { it.id }.sorted()
        }
    }

    class Factory @Inject constructor(
        private val modelConfigManager: ModelConfigManager,
        private val mmkv: MMKV
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(modelConfigManager, mmkv) as T
        }
    }
}
