package com.gao.chatbox.view.ui.home.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.util.ApiClient
import com.gao.chatbox.view.util.LanguageManager
import com.gao.chatbox.view.util.ModelConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SettingsViewModel(
    private val modelConfigManager: ModelConfigManager,
    private val dataStore: DataStore<Preferences>,
    private val languageManager: LanguageManager
) : ViewModel() {

    companion object {
        private val KEY_SHOW_CHAR_COUNT = booleanPreferencesKey("ui_show_char_count")
        private val KEY_SHOW_TOKEN_COUNT = booleanPreferencesKey("ui_show_token_count")
        private val KEY_SHOW_MODEL_NAME = booleanPreferencesKey("ui_show_model_name")
        private val KEY_SHOW_TIMESTAMP = booleanPreferencesKey("ui_show_timestamp")
        private val KEY_WEB_SEARCH = booleanPreferencesKey("capability_web_search")
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

    // Directly expose languageManager's StateFlow
    val currentLanguage: StateFlow<String> = languageManager.currentLanguage

    init {
        viewModelScope.launch {
            modelConfigManager.init()
            refreshModels()
            loadSettings()
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _models.value = modelConfigManager.getAll()
        }
    }

    private suspend fun loadSettings() {
        dataStore.data.collect { prefs ->
            _showCharCount.value = prefs[KEY_SHOW_CHAR_COUNT] ?: false
            _showTokenCount.value = prefs[KEY_SHOW_TOKEN_COUNT] ?: false
            _showModelName.value = prefs[KEY_SHOW_MODEL_NAME] ?: false
            _showTimestamp.value = prefs[KEY_SHOW_TIMESTAMP] ?: false
            _webSearchEnabled.value = prefs[KEY_WEB_SEARCH] ?: false
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            languageManager.setLanguage(language)
        }
    }

    fun addModel(config: ModelConfig) {
        viewModelScope.launch {
            modelConfigManager.add(config)
            refreshModels()
        }
    }

    fun updateModel(config: ModelConfig) {
        viewModelScope.launch {
            modelConfigManager.update(config)
            refreshModels()
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            modelConfigManager.delete(id)
            refreshModels()
        }
    }

    fun updateUiSetting(setting: SettingsAdapter.UiSetting, enabled: Boolean) {
        val key = when (setting) {
            SettingsAdapter.UiSetting.CHAR_COUNT -> KEY_SHOW_CHAR_COUNT
            SettingsAdapter.UiSetting.TOKEN_COUNT -> KEY_SHOW_TOKEN_COUNT
            SettingsAdapter.UiSetting.MODEL_NAME -> KEY_SHOW_MODEL_NAME
            SettingsAdapter.UiSetting.TIMESTAMP -> KEY_SHOW_TIMESTAMP
            SettingsAdapter.UiSetting.LANGUAGE -> return // Language is handled separately
        }
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[key] = enabled
            }
        }
    }

    fun updateCapabilitySetting(setting: SettingsAdapter.CapabilitySetting, enabled: Boolean) {
        when (setting) {
            SettingsAdapter.CapabilitySetting.WEB_SEARCH -> {
                viewModelScope.launch {
                    dataStore.edit { prefs ->
                        prefs[KEY_WEB_SEARCH] = enabled
                    }
                }
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
        private val dataStore: DataStore<Preferences>,
        private val languageManager: LanguageManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(modelConfigManager, dataStore, languageManager) as T
        }
    }
}
