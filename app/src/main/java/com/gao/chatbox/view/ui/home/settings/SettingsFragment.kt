package com.gao.chatbox.view.ui.home.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.model.ModelConfig.Companion.API_TYPE_ANTHROPIC
import com.gao.chatbox.view.data.model.ModelConfig.Companion.API_TYPE_OPENAI
import com.gao.chatbox.view.util.ApiClient
import com.gao.chatbox.view.util.ModelConfigManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private lateinit var rvSettings: RecyclerView
    private var adapter: SettingsAdapter? = null

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    companion object {
        private const val KEY_SHOW_CHAR_COUNT = "ui_show_char_count"
        private const val KEY_SHOW_TOKEN_COUNT = "ui_show_token_count"
        private const val KEY_SHOW_MODEL_NAME = "ui_show_model_name"
        private const val KEY_SHOW_TIMESTAMP = "ui_show_timestamp"
        private const val KEY_WEB_SEARCH = "capability_web_search"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ModelConfigManager.init()
        rvSettings = view.findViewById(R.id.rv_settings)
        rvSettings.layoutManager = LinearLayoutManager(requireContext())

        setupAdapter()
    }

    private fun setupAdapter() {
        adapter = SettingsAdapter(
            onModelClick = { config -> showModelDialog(config) },
            onModelLongClick = { view, config -> showModelPopupMenu(view, config) },
            onAddModelClick = { showModelDialog(null) },
            onUiSwitchChanged = { setting, checked ->
                val key = when (setting) {
                    SettingsAdapter.UiSetting.CHAR_COUNT -> KEY_SHOW_CHAR_COUNT
                    SettingsAdapter.UiSetting.TOKEN_COUNT -> KEY_SHOW_TOKEN_COUNT
                    SettingsAdapter.UiSetting.MODEL_NAME -> KEY_SHOW_MODEL_NAME
                    SettingsAdapter.UiSetting.TIMESTAMP -> KEY_SHOW_TIMESTAMP
                }
                mmkv.encode(key, checked)
            },
            onCapabilitySwitchChanged = { setting, checked ->
                when (setting) {
                    SettingsAdapter.CapabilitySetting.WEB_SEARCH -> mmkv.encode(KEY_WEB_SEARCH, checked)
                }
            }
        )

        adapter?.apply {
            showCharCount = mmkv.decodeBool(KEY_SHOW_CHAR_COUNT, false)
            showTokenCount = mmkv.decodeBool(KEY_SHOW_TOKEN_COUNT, false)
            showModelName = mmkv.decodeBool(KEY_SHOW_MODEL_NAME, false)
            showTimestamp = mmkv.decodeBool(KEY_SHOW_TIMESTAMP, false)
            webSearchEnabled = mmkv.decodeBool(KEY_WEB_SEARCH, false)
        }

        rvSettings.adapter = adapter
        refreshModels()
    }

    private fun refreshModels() {
        val models = ModelConfigManager.getAll()
        adapter?.setModels(models)
    }

    private fun showModelPopupMenu(anchorView: View, config: ModelConfig) {
        val popup = PopupMenu(requireContext(), anchorView)
        popup.menuInflater.inflate(R.menu.menu_prompt_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    showModelDialog(config)
                    true
                }
                R.id.action_delete -> {
                    showDeleteModelConfirm(config)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDeleteModelConfirm(config: ModelConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_model_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                ModelConfigManager.delete(config.id)
                refreshModels()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showModelDialog(existing: ModelConfig?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_model_config, null)

        val etName = dialogView.findViewById<TextInputEditText>(R.id.et_name)
        val spinnerApiType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_api_type)
        val etApiUrl = dialogView.findViewById<TextInputEditText>(R.id.et_api_url)
        val etApiKey = dialogView.findViewById<TextInputEditText>(R.id.et_api_key)
        val layoutOpenaiModel = dialogView.findViewById<LinearLayout>(R.id.layout_openai_model)
        val spinnerDefaultModel = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_default_model)
        val btnFetchModels = dialogView.findViewById<MaterialButton>(R.id.btn_fetch_models)
        val progressFetch = dialogView.findViewById<ProgressBar>(R.id.progress_fetch)
        val tilAnthropicModel = dialogView.findViewById<TextInputLayout>(R.id.til_anthropic_model)
        val etAnthropicModel = dialogView.findViewById<TextInputEditText>(R.id.et_anthropic_model)
        val etContextLimit = dialogView.findViewById<TextInputEditText>(R.id.et_context_limit)
        val tvTemperatureLabel = dialogView.findViewById<TextView>(R.id.tv_temperature_label)
        val sliderTemperature = dialogView.findViewById<Slider>(R.id.slider_temperature)
        val switchDefault = dialogView.findViewById<MaterialSwitch>(R.id.switch_default)

        // 已获取的模型列表（OpenAI 模式用于保存时写入 ModelConfig.models）
        var fetchedModels = mutableListOf<String>()

        // API 类型切换逻辑
        val apiTypes = listOf(API_TYPE_OPENAI, API_TYPE_ANTHROPIC)
        val apiTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line,
            listOf(getString(R.string.api_type_openai), getString(R.string.api_type_anthropic)))
        spinnerApiType.setAdapter(apiTypeAdapter)

        fun updateUiForApiType(apiType: String) {
            if (apiType == API_TYPE_OPENAI) {
                layoutOpenaiModel.visibility = View.VISIBLE
                btnFetchModels.visibility = View.VISIBLE
                progressFetch.visibility = View.GONE
                tilAnthropicModel.visibility = View.GONE
            } else {
                layoutOpenaiModel.visibility = View.GONE
                btnFetchModels.visibility = View.GONE
                progressFetch.visibility = View.GONE
                tilAnthropicModel.visibility = View.VISIBLE
            }
        }

        spinnerApiType.setOnItemClickListener { _, _, position, _ ->
            val selectedType = apiTypes[position]
            updateUiForApiType(selectedType)
            // 切换时填入默认 URL
            if (etApiUrl.text.isNullOrEmpty()) {
                when (selectedType) {
                    API_TYPE_OPENAI -> etApiUrl.setText("https://api.openai.com/v1")
                    API_TYPE_ANTHROPIC -> etApiUrl.setText("https://api.anthropic.com/v1")
                }
            }
            // 切换到 Anthropic 时填入默认上下文限制
            if (selectedType == API_TYPE_ANTHROPIC && etContextLimit.text.isNullOrEmpty()) {
                etContextLimit.setText("200000")
            }
        }

        // 预填充已有数据
        if (existing != null) {
            etName.setText(existing.name)
            etApiUrl.setText(existing.apiUrl)
            etApiKey.setText(existing.apiKey)
            etContextLimit.setText(existing.contextLimit.toString())
            sliderTemperature.value = (existing.temperature * 100).coerceIn(0f, 100f)
            switchDefault.isChecked = existing.isDefault
            tvTemperatureLabel.text = getString(R.string.label_temperature, existing.temperature)

            // 设置 API 类型
            val apiTypeIndex = apiTypes.indexOf(existing.apiType).coerceAtLeast(0)
            spinnerApiType.setText(apiTypeAdapter.getItem(apiTypeIndex), false)
            updateUiForApiType(existing.apiType)

            if (existing.apiType == API_TYPE_OPENAI) {
                if (existing.models.isNotEmpty()) {
                    fetchedModels.addAll(existing.models)
                    val spAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, existing.models)
                    spinnerDefaultModel.setAdapter(spAdapter)
                    if (existing.defaultModel.isNotEmpty()) {
                        spinnerDefaultModel.setText(existing.defaultModel, false)
                    }
                }
            } else {
                etAnthropicModel.setText(existing.defaultModel)
            }
        } else {
            sliderTemperature.value = 70f
            tvTemperatureLabel.text = getString(R.string.label_temperature, 0.7f)
            // 默认选中 OpenAI
            spinnerApiType.setText(apiTypeAdapter.getItem(0), false)
            updateUiForApiType(API_TYPE_OPENAI)
        }

        // 温度滑块
        sliderTemperature.addOnChangeListener { _, value, _ ->
            val temp = value / 100f
            tvTemperatureLabel.text = getString(R.string.label_temperature, temp)
        }

        // 获取模型按钮（仅 OpenAI）
        btnFetchModels.setOnClickListener {
            val apiUrl = etApiUrl.text?.toString()?.trim() ?: ""
            val apiKey = etApiKey.text?.toString()?.trim() ?: ""
            if (apiUrl.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_enter_url_and_key, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnFetchModels.isEnabled = false
            progressFetch.visibility = View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        ApiClient.fetchModels(apiUrl, apiKey)
                    }
                    val modelIds = response.data.map { it.id }.sorted()
                    fetchedModels.clear()
                    fetchedModels.addAll(modelIds)

                    val spAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modelIds)
                    spinnerDefaultModel.setAdapter(spAdapter)
                    if (modelIds.isNotEmpty()) {
                        spinnerDefaultModel.setText(modelIds[0], false)
                    }

                    if (etContextLimit.text.isNullOrEmpty()) {
                        etContextLimit.setText("4096")
                    }

                    Toast.makeText(requireContext(), getString(R.string.msg_fetch_success, modelIds.size), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.msg_fetch_failed, e.message ?: "Unknown"), Toast.LENGTH_LONG).show()
                } finally {
                    btnFetchModels.isEnabled = true
                    progressFetch.visibility = View.GONE
                }
            }
        }

        val titleRes = if (existing != null) R.string.dialog_title_edit_model else R.string.dialog_title_add_model

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) return@setPositiveButton

                val currentApiType = if (spinnerApiType.text.toString() == getString(R.string.api_type_anthropic))
                    API_TYPE_ANTHROPIC else API_TYPE_OPENAI
                val apiUrl = etApiUrl.text?.toString()?.trim() ?: ""
                val apiKey = etApiKey.text?.toString()?.trim() ?: ""
                val contextLimit = etContextLimit.text?.toString()?.toIntOrNull() ?: 4096
                val temperature = sliderTemperature.value / 100f
                val isDefault = switchDefault.isChecked

                val defaultModel: String
                val models: List<String>

                if (currentApiType == API_TYPE_OPENAI) {
                    defaultModel = spinnerDefaultModel.text?.toString()?.trim() ?: ""
                    models = fetchedModels.toList()
                } else {
                    defaultModel = etAnthropicModel.text?.toString()?.trim() ?: ""
                    models = if (defaultModel.isNotEmpty()) listOf(defaultModel) else emptyList()
                }

                val config = ModelConfig(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    apiType = currentApiType,
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    models = models,
                    defaultModel = defaultModel,
                    contextLimit = contextLimit,
                    temperature = temperature,
                    isDefault = isDefault,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )

                if (existing != null) {
                    ModelConfigManager.update(config)
                } else {
                    ModelConfigManager.add(config)
                }
                refreshModels()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
