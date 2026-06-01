package com.gao.chatbox.view.ui.home.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        val etTag = dialogView.findViewById<TextInputEditText>(R.id.et_name)
        val etApiType = dialogView.findViewById<TextInputEditText>(R.id.et_api_type)
        val etApiUrl = dialogView.findViewById<TextInputEditText>(R.id.et_api_url)
        val etApiKey = dialogView.findViewById<TextInputEditText>(R.id.et_api_key)
        val layoutOpenaiModel = dialogView.findViewById<LinearLayout>(R.id.layout_openai_model)
        val etDefaultModel = dialogView.findViewById<TextInputEditText>(R.id.et_default_model)
        val btnFetchModels = dialogView.findViewById<MaterialButton>(R.id.btn_fetch_models)
        val progressFetch = dialogView.findViewById<ProgressBar>(R.id.progress_fetch)
        val tilAnthropicModel = dialogView.findViewById<TextInputLayout>(R.id.til_anthropic_model)
        val etAnthropicModel = dialogView.findViewById<TextInputEditText>(R.id.et_anthropic_model)
        val etContextLimit = dialogView.findViewById<TextInputEditText>(R.id.et_context_limit)
        val tvTemperatureLabel = dialogView.findViewById<TextView>(R.id.tv_temperature_label)
        val sliderTemperature = dialogView.findViewById<Slider>(R.id.slider_temperature)
        val switchDefault = dialogView.findViewById<MaterialSwitch>(R.id.switch_default)

        // 状态变量
        var fetchedModels = mutableListOf<String>()
        var selectedApiType = existing?.apiType ?: API_TYPE_OPENAI
        var selectedDefaultModel = existing?.defaultModel ?: ""

        val apiTypeLabels = listOf(
            getString(R.string.api_type_openai),
            getString(R.string.api_type_anthropic)
        )
        val apiTypeValues = listOf(API_TYPE_OPENAI, API_TYPE_ANTHROPIC)

        fun updateUiForApiType(apiType: String) {
            selectedApiType = apiType
            etApiType.setText(if (apiType == API_TYPE_ANTHROPIC) apiTypeLabels[1] else apiTypeLabels[0])
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

        // API 类型选择（弹窗）
        etApiType.setOnClickListener {
            val currentIndex = apiTypeValues.indexOf(selectedApiType).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_api_type)
                .setSingleChoiceItems(apiTypeLabels.toTypedArray(), currentIndex) { dialog, which ->
                    val newType = apiTypeValues[which]
                    updateUiForApiType(newType)
                    if (etApiUrl.text.isNullOrEmpty()) {
                        when (newType) {
                            API_TYPE_OPENAI -> etApiUrl.setText("https://api.openai.com/v1")
                            API_TYPE_ANTHROPIC -> etApiUrl.setText("https://api.anthropic.com/v1")
                        }
                    }
                    if (newType == API_TYPE_ANTHROPIC && etContextLimit.text.isNullOrEmpty()) {
                        etContextLimit.setText("200000")
                    }
                    dialog.dismiss()
                }
                .show()
        }

        // 默认模型选择（弹窗，仅 OpenAI）
        etDefaultModel.setOnClickListener {
            if (fetchedModels.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_fetch_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentIndex = fetchedModels.indexOf(selectedDefaultModel).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_default_model)
                .setSingleChoiceItems(fetchedModels.toTypedArray(), currentIndex) { dialog, which ->
                    selectedDefaultModel = fetchedModels[which]
                    etDefaultModel.setText(selectedDefaultModel)
                    dialog.dismiss()
                }
                .show()
        }

        // 预填充已有数据
        if (existing != null) {
            etTag.setText(existing.tag)
            etApiUrl.setText(existing.apiUrl)
            etApiKey.setText(existing.apiKey)
            etContextLimit.setText(existing.contextLimit.toString())
            sliderTemperature.value = (existing.temperature * 100).coerceIn(0f, 100f)
            switchDefault.isChecked = existing.isDefault
            tvTemperatureLabel.text = getString(R.string.label_temperature, existing.temperature)

            updateUiForApiType(existing.apiType)

            if (existing.apiType == API_TYPE_OPENAI) {
                if (existing.models.isNotEmpty()) {
                    fetchedModels.addAll(existing.models)
                }
                if (existing.defaultModel.isNotEmpty()) {
                    etDefaultModel.setText(existing.defaultModel)
                }
            } else {
                etAnthropicModel.setText(existing.defaultModel)
            }
        } else {
            sliderTemperature.value = 70f
            tvTemperatureLabel.text = getString(R.string.label_temperature, 0.7f)
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

                    if (modelIds.isNotEmpty()) {
                        selectedDefaultModel = modelIds[0]
                        etDefaultModel.setText(selectedDefaultModel)
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
                val tag = etTag.text?.toString()?.trim() ?: ""
                if (tag.isEmpty()) return@setPositiveButton

                val apiUrl = etApiUrl.text?.toString()?.trim() ?: ""
                val apiKey = etApiKey.text?.toString()?.trim() ?: ""
                val contextLimit = etContextLimit.text?.toString()?.toIntOrNull() ?: 4096
                val temperature = sliderTemperature.value / 100f
                val isDefault = switchDefault.isChecked

                val defaultModel: String
                val models: List<String>

                if (selectedApiType == API_TYPE_OPENAI) {
                    defaultModel = selectedDefaultModel
                    models = fetchedModels.toList()
                } else {
                    defaultModel = etAnthropicModel.text?.toString()?.trim() ?: ""
                    models = if (defaultModel.isNotEmpty()) listOf(defaultModel) else emptyList()
                }

                val config = ModelConfig(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    tag = tag,
                    apiType = selectedApiType,
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
