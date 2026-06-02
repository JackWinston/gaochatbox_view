package com.gao.chatbox.view.ui.home.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.data.model.ModelConfig.Companion.API_TYPE_ANTHROPIC
import com.gao.chatbox.view.data.model.ModelConfig.Companion.API_TYPE_OPENAI
import com.gao.chatbox.view.databinding.DialogModelConfigBinding
import com.gao.chatbox.view.databinding.FragmentSettingsBinding
import com.gao.chatbox.view.util.ModelContextLimitResolver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var adapter: SettingsAdapter? = null
    private val viewModel: SettingsViewModel by viewModels {
        (requireActivity().application as ChatBoxApp).appComponent.settingsViewModelFactory()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSettings.layoutManager = LinearLayoutManager(requireContext())

        setupAdapter()
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupAdapter() {
        adapter = SettingsAdapter(
            onModelClick = { config -> showModelDialog(config) },
            onModelLongClick = { view, config -> showModelPopupMenu(view, config) },
            onAddModelClick = { showModelDialog(null) },
            onUiSwitchChanged = { setting, checked ->
                viewModel.updateUiSetting(setting, checked)
            },
            onCapabilitySwitchChanged = { setting, checked ->
                viewModel.updateCapabilitySetting(setting, checked)
            },
            onMaxToolCallRoundsClick = { showMaxToolCallRoundsDialog() },
            onLanguageClick = { showLanguageDialog() },
            onThemeClick = { showThemeDialog() }
        )
        binding.rvSettings.adapter = adapter
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.models.collect { models ->
                        adapter?.setModels(models)
                    }
                }
                launch {
                    viewModel.showCharCount.collect {
                        adapter?.showCharCount = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.showTokenCount.collect {
                        adapter?.showTokenCount = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.showModelName.collect {
                        adapter?.showModelName = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.showTimestamp.collect {
                        adapter?.showTimestamp = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.webSearchEnabled.collect {
                        adapter?.webSearchEnabled = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.maxToolCallRounds.collect {
                        adapter?.maxToolCallRounds = it
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.currentLanguage.collect { language ->
                        adapter?.currentLanguage = language
                        adapter?.rebuildItems()
                    }
                }
                launch {
                    viewModel.currentTheme.collect { theme ->
                        adapter?.currentTheme = theme
                        adapter?.rebuildItems()
                    }
                }
            }
        }
    }

    private fun showMaxToolCallRoundsDialog() {
        val editText = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(viewModel.maxToolCallRounds.value.toString())
            setSelection(text?.length ?: 0)
            setPadding(64, 32, 64, 16)
            hint = getString(R.string.hint_max_tool_call_rounds)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_max_tool_call_rounds)
            .setView(editText)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val value = editText.text?.toString()?.trim()?.toIntOrNull()
                if (value == null ||
                    value !in SettingsViewModel.MIN_MAX_TOOL_CALL_ROUNDS..SettingsViewModel.MAX_MAX_TOOL_CALL_ROUNDS
                ) {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.msg_invalid_max_tool_call_rounds,
                            SettingsViewModel.MIN_MAX_TOOL_CALL_ROUNDS,
                            SettingsViewModel.MAX_MAX_TOOL_CALL_ROUNDS
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                viewModel.updateMaxToolCallRounds(value)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
                viewModel.deleteModel(config.id)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val themeValues = arrayOf("system", "light", "dark")

        val currentTheme = viewModel.currentTheme.value
        val currentIndex = themeValues.indexOf(currentTheme).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ui_theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val selectedTheme = themeValues[which]
                if (selectedTheme != currentTheme) {
                    viewModel.setTheme(selectedTheme)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_chinese),
            getString(R.string.language_english)
        )
        val languageValues = arrayOf("system", "zh", "en")

        val currentLanguage = viewModel.currentLanguage.value
        val currentIndex = languageValues.indexOf(currentLanguage).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ui_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLanguage = languageValues[which]
                if (selectedLanguage != currentLanguage) {
                    viewModel.setLanguage(selectedLanguage)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showModelDialog(existing: ModelConfig?) {
        val dialogBinding = DialogModelConfigBinding.inflate(layoutInflater)

        var fetchedModels = mutableListOf<String>()
        var selectedApiType = existing?.apiType ?: API_TYPE_OPENAI
        var selectedDefaultModel = existing?.defaultModel ?: ""
        var detectedContextLimit = existing?.detectedContextLimit?.takeIf { it > 0 }
        var contextLimitManual = existing?.contextLimitManuallySet != false
        var suppressContextWatcher = false

        val apiTypeLabels = listOf(
            getString(R.string.api_type_openai),
            getString(R.string.api_type_anthropic)
        )
        val apiTypeValues = listOf(API_TYPE_OPENAI, API_TYPE_ANTHROPIC)

        fun currentModelName(): String {
            return if (selectedApiType == API_TYPE_OPENAI) {
                selectedDefaultModel
            } else {
                dialogBinding.etAnthropicModel.text?.toString()?.trim().orEmpty()
            }
        }

        fun updateContextHelper() {
            dialogBinding.tilContextLimit.helperText = if (contextLimitManual) {
                getString(R.string.context_limit_helper_manual)
            } else {
                getString(R.string.context_limit_helper_auto)
            }
        }

        fun setContextLimitText(value: Int) {
            suppressContextWatcher = true
            dialogBinding.etContextLimit.setText(value.toString())
            suppressContextWatcher = false
        }

        fun applyResolvedContextLimit(value: Int) {
            detectedContextLimit = value
            if (!contextLimitManual) {
                setContextLimitText(value)
                updateContextHelper()
            }
        }

        fun resolveContextLimitIfPossible(showErrorToast: Boolean = false) {
            if (contextLimitManual) return
            val apiUrl = dialogBinding.etApiUrl.text?.toString()?.trim().orEmpty()
            val apiKey = dialogBinding.etApiKey.text?.toString()?.trim().orEmpty()
            val modelName = currentModelName()
            if (apiUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) {
                applyResolvedContextLimit(ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT)
                return
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resolved = viewModel.resolveContextLimit(
                        apiType = selectedApiType,
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        modelName = modelName
                    )
                    applyResolvedContextLimit(resolved)
                } catch (e: Exception) {
                    applyResolvedContextLimit(ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT)
                    if (showErrorToast) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.msg_fetch_failed, e.message ?: "Unknown"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        fun updateUiForApiType(apiType: String) {
            selectedApiType = apiType
            dialogBinding.etApiType.setText(if (apiType == API_TYPE_ANTHROPIC) apiTypeLabels[1] else apiTypeLabels[0])
            if (apiType == API_TYPE_OPENAI) {
                dialogBinding.layoutOpenaiModel.visibility = View.VISIBLE
                dialogBinding.btnFetchModels.visibility = View.VISIBLE
                dialogBinding.progressFetch.visibility = View.GONE
                dialogBinding.tilAnthropicModel.visibility = View.GONE
                dialogBinding.sliderTemperature.valueTo = 200f
                dialogBinding.sliderTemperature.stepSize = 10f
            } else {
                dialogBinding.layoutOpenaiModel.visibility = View.GONE
                dialogBinding.btnFetchModels.visibility = View.GONE
                dialogBinding.progressFetch.visibility = View.GONE
                dialogBinding.tilAnthropicModel.visibility = View.VISIBLE
                dialogBinding.sliderTemperature.valueTo = 100f
                dialogBinding.sliderTemperature.stepSize = 5f
            }
            dialogBinding.sliderTemperature.value = dialogBinding.sliderTemperature.value.coerceIn(0f, dialogBinding.sliderTemperature.valueTo)
            if (!contextLimitManual) {
                resolveContextLimitIfPossible()
            }
        }

        dialogBinding.etApiType.setOnClickListener {
            val currentIndex = apiTypeValues.indexOf(selectedApiType).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_api_type)
                .setSingleChoiceItems(apiTypeLabels.toTypedArray(), currentIndex) { dialog, which ->
                    val newType = apiTypeValues[which]
                    updateUiForApiType(newType)
                    dialog.dismiss()
                }
                .show()
        }

        dialogBinding.etDefaultModel.setOnClickListener {
            if (fetchedModels.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_fetch_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentIndex = fetchedModels.indexOf(selectedDefaultModel).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_default_model)
                .setSingleChoiceItems(fetchedModels.toTypedArray(), currentIndex) { dialog, which ->
                    selectedDefaultModel = fetchedModels[which]
                    dialogBinding.etDefaultModel.setText(selectedDefaultModel)
                    resolveContextLimitIfPossible()
                    dialog.dismiss()
                }
                .show()
        }

        dialogBinding.etContextLimit.doAfterTextChanged {
            if (suppressContextWatcher) return@doAfterTextChanged
            contextLimitManual = true
            updateContextHelper()
        }

        dialogBinding.etAnthropicModel.doAfterTextChanged {
            if (selectedApiType == API_TYPE_ANTHROPIC) {
                selectedDefaultModel = it?.toString()?.trim().orEmpty()
            }
        }
        dialogBinding.etAnthropicModel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                resolveContextLimitIfPossible()
            }
        }
        dialogBinding.etApiUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                resolveContextLimitIfPossible()
            }
        }
        dialogBinding.etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                resolveContextLimitIfPossible()
            }
        }

        if (existing != null) {
            dialogBinding.etName.setText(existing.tag)
            dialogBinding.etApiUrl.setText(existing.apiUrl)
            dialogBinding.etApiKey.setText(existing.apiKey)
            dialogBinding.switchDefault.isChecked = existing.isDefault
            setContextLimitText(
                existing.contextLimit.takeIf { it > 0 }
                    ?: detectedContextLimit
                    ?: ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT
            )
            updateContextHelper()

            updateUiForApiType(existing.apiType)
            dialogBinding.sliderTemperature.value = (existing.temperature * 100).coerceIn(0f, dialogBinding.sliderTemperature.valueTo)
            dialogBinding.tvTemperatureLabel.text = getString(R.string.label_temperature, existing.temperature)

            if (existing.apiType == API_TYPE_OPENAI) {
                if (existing.models.isNotEmpty()) {
                    fetchedModels.addAll(existing.models)
                }
                if (existing.defaultModel.isNotEmpty()) {
                    dialogBinding.etDefaultModel.setText(existing.defaultModel)
                }
            } else {
                dialogBinding.etAnthropicModel.setText(existing.defaultModel)
            }
            if (!contextLimitManual) {
                resolveContextLimitIfPossible()
            }
        } else {
            dialogBinding.sliderTemperature.value = 70f
            dialogBinding.tvTemperatureLabel.text = getString(R.string.label_temperature, 0.7f)
            contextLimitManual = false
            setContextLimitText(ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT)
            updateContextHelper()
            updateUiForApiType(API_TYPE_OPENAI)
        }

        dialogBinding.sliderTemperature.addOnChangeListener { _, value, _ ->
            val temp = value / 100f
            dialogBinding.tvTemperatureLabel.text = getString(R.string.label_temperature, temp)
        }

        dialogBinding.btnFetchModels.setOnClickListener {
            val apiUrl = dialogBinding.etApiUrl.text?.toString()?.trim() ?: ""
            val apiKey = dialogBinding.etApiKey.text?.toString()?.trim() ?: ""
            if (apiUrl.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_enter_url_and_key, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnFetchModels.isEnabled = false
            dialogBinding.progressFetch.visibility = View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val modelIds = viewModel.fetchModels(apiUrl, apiKey)
                    fetchedModels.clear()
                    fetchedModels.addAll(modelIds)

                    if (modelIds.isNotEmpty()) {
                        selectedDefaultModel = selectedDefaultModel.takeIf { it in modelIds } ?: modelIds[0]
                        dialogBinding.etDefaultModel.setText(selectedDefaultModel)
                    }
                    resolveContextLimitIfPossible()

                    Toast.makeText(requireContext(), getString(R.string.msg_fetch_success, modelIds.size), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.msg_fetch_failed, e.message ?: "Unknown"), Toast.LENGTH_LONG).show()
                } finally {
                    dialogBinding.btnFetchModels.isEnabled = true
                    dialogBinding.progressFetch.visibility = View.GONE
                }
            }
        }

        val titleRes = if (existing != null) R.string.dialog_title_edit_model else R.string.dialog_title_add_model

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val tag = dialogBinding.etName.text?.toString()?.trim() ?: ""
                if (tag.isEmpty()) return@setPositiveButton

                val apiUrl = dialogBinding.etApiUrl.text?.toString()?.trim() ?: ""
                val apiKey = dialogBinding.etApiKey.text?.toString()?.trim() ?: ""
                val contextLimit = dialogBinding.etContextLimit.text?.toString()?.toIntOrNull()
                    ?: detectedContextLimit
                    ?: ModelContextLimitResolver.DEFAULT_CONTEXT_LIMIT
                val temperature = dialogBinding.sliderTemperature.value / 100f
                val isDefault = dialogBinding.switchDefault.isChecked

                val defaultModel: String
                val models: List<String>

                if (selectedApiType == API_TYPE_OPENAI) {
                    defaultModel = selectedDefaultModel
                    models = fetchedModels.toList()
                } else {
                    defaultModel = dialogBinding.etAnthropicModel.text?.toString()?.trim() ?: ""
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
                    detectedContextLimit = detectedContextLimit,
                    contextLimitManuallySet = contextLimitManual,
                    temperature = temperature,
                    isDefault = isDefault,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )

                if (existing != null) {
                    viewModel.updateModel(config)
                } else {
                    viewModel.addModel(config)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
