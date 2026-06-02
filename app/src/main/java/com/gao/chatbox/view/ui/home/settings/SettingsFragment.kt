package com.gao.chatbox.view.ui.home.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
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
            }
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
                    viewModel.showCharCount.collect { adapter?.showCharCount = it }
                }
                launch {
                    viewModel.showTokenCount.collect { adapter?.showTokenCount = it }
                }
                launch {
                    viewModel.showModelName.collect { adapter?.showModelName = it }
                }
                launch {
                    viewModel.showTimestamp.collect { adapter?.showTimestamp = it }
                }
                launch {
                    viewModel.webSearchEnabled.collect { adapter?.webSearchEnabled = it }
                }
            }
        }
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

    private fun showModelDialog(existing: ModelConfig?) {
        val dialogBinding = DialogModelConfigBinding.inflate(layoutInflater)

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
        }

        dialogBinding.etApiType.setOnClickListener {
            val currentIndex = apiTypeValues.indexOf(selectedApiType).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_api_type)
                .setSingleChoiceItems(apiTypeLabels.toTypedArray(), currentIndex) { dialog, which ->
                    val newType = apiTypeValues[which]
                    updateUiForApiType(newType)
                    if (newType == API_TYPE_ANTHROPIC && dialogBinding.etContextLimit.text.isNullOrEmpty()) {
                        dialogBinding.etContextLimit.setText("200000")
                    }
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
                    dialog.dismiss()
                }
                .show()
        }

        if (existing != null) {
            dialogBinding.etName.setText(existing.tag)
            dialogBinding.etApiUrl.setText(existing.apiUrl)
            dialogBinding.etApiKey.setText(existing.apiKey)
            dialogBinding.etContextLimit.setText(existing.contextLimit.toString())
            dialogBinding.switchDefault.isChecked = existing.isDefault

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
        } else {
            dialogBinding.sliderTemperature.value = 70f
            dialogBinding.tvTemperatureLabel.text = getString(R.string.label_temperature, 0.7f)
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
                        selectedDefaultModel = modelIds[0]
                        dialogBinding.etDefaultModel.setText(selectedDefaultModel)
                    }

                    if (dialogBinding.etContextLimit.text.isNullOrEmpty()) {
                        dialogBinding.etContextLimit.setText("4096")
                    }

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
                val contextLimit = dialogBinding.etContextLimit.text?.toString()?.toIntOrNull() ?: 4096
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
