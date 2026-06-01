package com.gao.chatbox.view.ui.home.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.ModelConfig
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsAdapter(
    private val onModelClick: (ModelConfig) -> Unit,
    private val onModelLongClick: (View, ModelConfig) -> Unit,
    private val onAddModelClick: () -> Unit,
    private val onUiSwitchChanged: (UiSetting, Boolean) -> Unit,
    private val onCapabilitySwitchChanged: (CapabilitySetting, Boolean) -> Unit
) : BaseMultiItemAdapter<SettingsAdapter.SettingsItem>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MODEL_ITEM = 1
        const val TYPE_ADD_MODEL = 2
    }

    enum class Section { MODEL, UI, CAPABILITY }
    enum class UiSetting { CHAR_COUNT, TOKEN_COUNT, MODEL_NAME, TIMESTAMP }
    enum class CapabilitySetting { WEB_SEARCH }

    sealed class SettingsItem {
        class Header(val section: Section) : SettingsItem()
        class Model(val config: ModelConfig) : SettingsItem()
        class AddModel : SettingsItem()
        class UiSwitch(val setting: UiSetting) : SettingsItem()
        class CapabilitySwitch(val setting: CapabilitySetting) : SettingsItem()
    }

    private val expandedSections = mutableSetOf(Section.MODEL)
    private var models = listOf<ModelConfig>()

    var showCharCount = false
    var showTokenCount = false
    var showModelName = false
    var showTimestamp = false
    var webSearchEnabled = false

    init {
        onItemViewType { position, list ->
            when (list[position]) {
                is SettingsItem.Header -> TYPE_HEADER
                is SettingsItem.Model -> TYPE_MODEL_ITEM
                is SettingsItem.AddModel -> TYPE_ADD_MODEL
                is SettingsItem.UiSwitch -> TYPE_HEADER
                is SettingsItem.CapabilitySwitch -> TYPE_HEADER
            }
        }

        addItemType(TYPE_HEADER, object : OnMultiItem<SettingsItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_section_header, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: SettingsItem?) {
                when (item) {
                    is SettingsItem.Header -> bindHeader(holder, item)
                    is SettingsItem.UiSwitch -> bindUiSwitch(holder, item)
                    is SettingsItem.CapabilitySwitch -> bindCapabilitySwitch(holder, item)
                    else -> {}
                }
            }
        })

        addItemType(TYPE_MODEL_ITEM, object : OnMultiItem<SettingsItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_model_config, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: SettingsItem?) {
                val config = (item as? SettingsItem.Model)?.config ?: return
                holder.setText(R.id.tv_model_tag, config.tag)
                holder.setText(R.id.tv_model_url, config.apiUrl)
                holder.setGone(R.id.chip_default, !config.isDefault)

                holder.itemView.setOnClickListener { onModelClick(config) }
                holder.itemView.setOnLongClickListener { view ->
                    onModelLongClick(view, config)
                    true
                }
            }
        })

        addItemType(TYPE_ADD_MODEL, object : OnMultiItem<SettingsItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_add_model, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: SettingsItem?) {
                holder.itemView.setOnClickListener { onAddModelClick() }
            }
        })
    }

    private fun bindHeader(holder: QuickViewHolder, item: SettingsItem.Header) {
        val section = item.section
        val isExpanded = expandedSections.contains(section)

        holder.setText(R.id.tv_section_title, when (section) {
            Section.MODEL -> holder.itemView.context.getString(R.string.section_model)
            Section.UI -> holder.itemView.context.getString(R.string.section_ui)
            Section.CAPABILITY -> holder.itemView.context.getString(R.string.section_capability)
        })

        holder.getView<View>(R.id.iv_expand).animate()
            .rotation(if (isExpanded) 180f else 0f)
            .setDuration(200)
            .start()

        holder.setVisible(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, true)
        holder.itemView.setOnClickListener { toggleSection(section) }
    }

    private fun bindUiSwitch(holder: QuickViewHolder, item: SettingsItem.UiSwitch) {
        holder.setGone(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, false)

        val switchView = holder.getView<MaterialSwitch>(R.id.switch_section)
        switchView.setOnCheckedChangeListener(null)

        when (item.setting) {
            UiSetting.CHAR_COUNT -> {
                holder.setText(R.id.tv_section_title, holder.itemView.context.getString(R.string.ui_show_char_count))
                switchView.isChecked = showCharCount
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showCharCount = isChecked
                    onUiSwitchChanged(UiSetting.CHAR_COUNT, isChecked)
                }
            }
            UiSetting.TOKEN_COUNT -> {
                holder.setText(R.id.tv_section_title, holder.itemView.context.getString(R.string.ui_show_token_count))
                switchView.isChecked = showTokenCount
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showTokenCount = isChecked
                    onUiSwitchChanged(UiSetting.TOKEN_COUNT, isChecked)
                }
            }
            UiSetting.MODEL_NAME -> {
                holder.setText(R.id.tv_section_title, holder.itemView.context.getString(R.string.ui_show_model_name))
                switchView.isChecked = showModelName
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showModelName = isChecked
                    onUiSwitchChanged(UiSetting.MODEL_NAME, isChecked)
                }
            }
            UiSetting.TIMESTAMP -> {
                holder.setText(R.id.tv_section_title, holder.itemView.context.getString(R.string.ui_show_timestamp))
                switchView.isChecked = showTimestamp
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showTimestamp = isChecked
                    onUiSwitchChanged(UiSetting.TIMESTAMP, isChecked)
                }
            }
        }

        holder.itemView.setOnClickListener(null)
    }

    private fun bindCapabilitySwitch(holder: QuickViewHolder, item: SettingsItem.CapabilitySwitch) {
        holder.setGone(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, false)

        val switchView = holder.getView<MaterialSwitch>(R.id.switch_section)
        switchView.setOnCheckedChangeListener(null)

        when (item.setting) {
            CapabilitySetting.WEB_SEARCH -> {
                holder.setText(R.id.tv_section_title, holder.itemView.context.getString(R.string.capability_web_search))
                switchView.isChecked = webSearchEnabled
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    webSearchEnabled = isChecked
                    onCapabilitySwitchChanged(CapabilitySetting.WEB_SEARCH, isChecked)
                }
            }
        }

        holder.itemView.setOnClickListener(null)
    }

    fun setModels(newModels: List<ModelConfig>) {
        models = newModels
        rebuildItems()
    }

    fun rebuildItems() {
        val newItems = mutableListOf<SettingsItem>()

        // 模型设置
        newItems.add(SettingsItem.Header(Section.MODEL))
        if (expandedSections.contains(Section.MODEL)) {
            for (model in models) {
                newItems.add(SettingsItem.Model(model))
            }
            newItems.add(SettingsItem.AddModel())
        }

        // 界面设置
        newItems.add(SettingsItem.Header(Section.UI))
        if (expandedSections.contains(Section.UI)) {
            newItems.add(SettingsItem.UiSwitch(UiSetting.CHAR_COUNT))
            newItems.add(SettingsItem.UiSwitch(UiSetting.TOKEN_COUNT))
            newItems.add(SettingsItem.UiSwitch(UiSetting.MODEL_NAME))
            newItems.add(SettingsItem.UiSwitch(UiSetting.TIMESTAMP))
        }

        // 能力设置
        newItems.add(SettingsItem.Header(Section.CAPABILITY))
        if (expandedSections.contains(Section.CAPABILITY)) {
            newItems.add(SettingsItem.CapabilitySwitch(CapabilitySetting.WEB_SEARCH))
        }

        submitList(newItems)
    }

    private fun toggleSection(section: Section) {
        if (expandedSections.contains(section)) {
            expandedSections.remove(section)
        } else {
            expandedSections.add(section)
        }
        rebuildItems()
    }
}
