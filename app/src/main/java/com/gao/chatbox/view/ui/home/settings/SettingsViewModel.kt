package com.gao.chatbox.view.ui.home.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.util.ApiClient
import com.gao.chatbox.view.util.LanguageManager
import com.gao.chatbox.view.util.ModelConfigManager
import com.gao.chatbox.view.util.ModelContextLimitResolver
import com.gao.chatbox.view.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置页面的 ViewModel
 *
 * 职责：
 * - 管理模型配置的 CRUD 操作（通过 ModelConfigManager）
 * - 管理 UI 偏好设置的读写（通过 DataStore）
 * - 管理能力设置的读写（网页搜索、工具调用轮次）
 * - 提供模型列表获取和上下文限制解析功能
 * - 管理语言和主题设置（通过 LanguageManager / ThemeManager）
 *
 * 数据流：ViewModel → ModelConfigManager / DataStore / LanguageManager / ThemeManager
 */
class SettingsViewModel(
    private val modelConfigManager: ModelConfigManager,
    private val modelContextLimitResolver: ModelContextLimitResolver,
    private val dataStore: DataStore<Preferences>,
    private val languageManager: LanguageManager,
    private val themeManager: ThemeManager
) : ViewModel() {

    companion object {
        /** 默认最大工具调用轮次 */
        const val DEFAULT_MAX_TOOL_CALL_ROUNDS = 8
        /** 最小允许值 */
        const val MIN_MAX_TOOL_CALL_ROUNDS = 1
        /** 最大允许值 */
        const val MAX_MAX_TOOL_CALL_ROUNDS = 32

        // DataStore 偏好键定义
        private val KEY_SHOW_CHAR_COUNT = booleanPreferencesKey("ui_show_char_count")
        private val KEY_SHOW_TOKEN_COUNT = booleanPreferencesKey("ui_show_token_count")
        private val KEY_SHOW_MODEL_NAME = booleanPreferencesKey("ui_show_model_name")
        private val KEY_SHOW_TIMESTAMP = booleanPreferencesKey("ui_show_timestamp")
        private val KEY_WEB_SEARCH = booleanPreferencesKey("capability_web_search")
        private val KEY_MAX_TOOL_CALL_ROUNDS = intPreferencesKey("capability_max_tool_call_rounds")
    }

    /** 模型配置列表 */
    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: StateFlow<List<ModelConfig>> = _models

    // UI 偏好设置 StateFlow
    private val _showCharCount = MutableStateFlow(false)
    val showCharCount: StateFlow<Boolean> = _showCharCount

    private val _showTokenCount = MutableStateFlow(false)
    val showTokenCount: StateFlow<Boolean> = _showTokenCount

    private val _showModelName = MutableStateFlow(false)
    val showModelName: StateFlow<Boolean> = _showModelName

    private val _showTimestamp = MutableStateFlow(false)
    val showTimestamp: StateFlow<Boolean> = _showTimestamp

    // 能力设置 StateFlow
    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    private val _maxToolCallRounds = MutableStateFlow(DEFAULT_MAX_TOOL_CALL_ROUNDS)
    val maxToolCallRounds: StateFlow<Int> = _maxToolCallRounds

    /** 当前语言设置，直接暴露 LanguageManager 的 StateFlow */
    val currentLanguage: StateFlow<String> = languageManager.currentLanguage

    /** 当前主题设置，直接暴露 ThemeManager 的 StateFlow */
    val currentTheme: StateFlow<String> = themeManager.currentTheme

    init {
        viewModelScope.launch {
            modelConfigManager.init()
            refreshModels()
            loadSettings()
        }
    }

    /** 从 ModelConfigManager 刷新模型配置列表 */
    fun refreshModels() {
        viewModelScope.launch {
            _models.value = modelConfigManager.getAll()
        }
    }

    /**
     * 持续监听 DataStore 中的偏好设置变化
     * 收到变化后更新对应的 StateFlow，UI 层自动响应
     */
    private suspend fun loadSettings() {
        dataStore.data.collect { prefs ->
            _showCharCount.value = prefs[KEY_SHOW_CHAR_COUNT] ?: false
            _showTokenCount.value = prefs[KEY_SHOW_TOKEN_COUNT] ?: false
            _showModelName.value = prefs[KEY_SHOW_MODEL_NAME] ?: false
            _showTimestamp.value = prefs[KEY_SHOW_TIMESTAMP] ?: false
            _webSearchEnabled.value = prefs[KEY_WEB_SEARCH] ?: false
            _maxToolCallRounds.value =
                (prefs[KEY_MAX_TOOL_CALL_ROUNDS] ?: DEFAULT_MAX_TOOL_CALL_ROUNDS)
                    .coerceIn(MIN_MAX_TOOL_CALL_ROUNDS, MAX_MAX_TOOL_CALL_ROUNDS)
        }
    }

    /** 设置应用语言（system/zh/en） */
    fun setLanguage(language: String) {
        viewModelScope.launch {
            languageManager.setLanguage(language)
        }
    }

    /** 设置应用主题（system/light/dark） */
    fun setTheme(theme: String) {
        viewModelScope.launch {
            themeManager.setTheme(theme)
        }
    }

    /** 新增模型配置 */
    fun addModel(config: ModelConfig) {
        viewModelScope.launch {
            modelConfigManager.add(config)
            refreshModels()
        }
    }

    /** 更新模型配置 */
    fun updateModel(config: ModelConfig) {
        viewModelScope.launch {
            modelConfigManager.update(config)
            refreshModels()
        }
    }

    /** 删除模型配置 */
    fun deleteModel(id: String) {
        viewModelScope.launch {
            modelConfigManager.delete(id)
            refreshModels()
        }
    }

    /**
     * 更新 UI 偏好设置开关
     *
     * @param setting 设置项枚举
     * @param enabled 开关状态
     * LANGUAGE 和 THEME 由各自的 Manager 单独处理，此处直接 return
     */
    fun updateUiSetting(setting: SettingsAdapter.UiSetting, enabled: Boolean) {
        val key = when (setting) {
            SettingsAdapter.UiSetting.CHAR_COUNT -> KEY_SHOW_CHAR_COUNT
            SettingsAdapter.UiSetting.TOKEN_COUNT -> KEY_SHOW_TOKEN_COUNT
            SettingsAdapter.UiSetting.MODEL_NAME -> KEY_SHOW_MODEL_NAME
            SettingsAdapter.UiSetting.TIMESTAMP -> KEY_SHOW_TIMESTAMP
            SettingsAdapter.UiSetting.LANGUAGE -> return
            SettingsAdapter.UiSetting.THEME -> return
        }
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[key] = enabled
            }
        }
    }

    /**
     * 更新能力设置开关
     *
     * WEB_SEARCH: 持久化到 DataStore
     * MAX_TOOL_CALL_ROUNDS: 此处为 no-op，由 updateMaxToolCallRounds 单独处理
     */
    fun updateCapabilitySetting(setting: SettingsAdapter.CapabilitySetting, enabled: Boolean) {
        when (setting) {
            SettingsAdapter.CapabilitySetting.WEB_SEARCH -> {
                viewModelScope.launch {
                    dataStore.edit { prefs ->
                        prefs[KEY_WEB_SEARCH] = enabled
                    }
                }
            }
            SettingsAdapter.CapabilitySetting.MAX_TOOL_CALL_ROUNDS -> Unit
        }
    }

    /** 更新最大工具调用轮次，值会被限制在允许范围内 */
    fun updateMaxToolCallRounds(rounds: Int) {
        val clamped = rounds.coerceIn(MIN_MAX_TOOL_CALL_ROUNDS, MAX_MAX_TOOL_CALL_ROUNDS)
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_MAX_TOOL_CALL_ROUNDS] = clamped
            }
        }
    }

    /**
     * 从 API 获取可用模型列表
     *
     * @param apiUrl API 接口地址
     * @param apiKey 认证密钥
     * @return 模型 ID 列表（已排序）
     */
    suspend fun fetchModels(apiUrl: String, apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            val response = ApiClient.fetchModels(apiUrl, apiKey)
            response.data.map { it.id }.sorted()
        }
    }

    /**
     * 通过 API 动态解析模型的上下文限制
     *
     * @param apiType API 类型（openai/anthropic）
     * @param apiUrl API 地址
     * @param apiKey 认证密钥
     * @param modelName 模型名称
     * @return 上下文 token 限制值
     */
    suspend fun resolveContextLimit(
        apiType: String,
        apiUrl: String,
        apiKey: String,
        modelName: String
    ): Int {
        return withContext(Dispatchers.IO) {
            modelContextLimitResolver.resolve(apiType, apiUrl, apiKey, modelName)
        }
    }

    /**
     * 通过静态表解析模型的上下文限制（不需要网络请求）
     *
     * @return 上下文限制值，静态表中无此模型时返回 null
     */
    fun resolveContextLimitStatic(apiType: String, modelName: String): Int? {
        return modelContextLimitResolver.resolveStatic(apiType, modelName)
    }

    class Factory @Inject constructor(
        private val modelConfigManager: ModelConfigManager,
        private val modelContextLimitResolver: ModelContextLimitResolver,
        private val dataStore: DataStore<Preferences>,
        private val languageManager: LanguageManager,
        private val themeManager: ThemeManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                modelConfigManager,
                modelContextLimitResolver,
                dataStore,
                languageManager,
                themeManager
            ) as T
        }
    }
}
