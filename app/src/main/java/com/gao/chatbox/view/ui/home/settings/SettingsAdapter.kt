package com.gao.chatbox.view.ui.home.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.ModelConfig
import com.gao.chatbox.view.databinding.ItemAddModelBinding
import com.gao.chatbox.view.databinding.ItemModelConfigBinding
import com.gao.chatbox.view.databinding.ItemSectionHeaderBinding

class SettingsAdapter(
    private val onModelClick: (ModelConfig) -> Unit,
    private val onModelLongClick: (View, ModelConfig) -> Unit,
    private val onAddModelClick: () -> Unit,
    private val onUiSwitchChanged: (UiSetting, Boolean) -> Unit,
    private val onCapabilitySwitchChanged: (CapabilitySetting, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MODEL_ITEM = 1
        private const val TYPE_ADD_MODEL = 2
    }

    enum class Section { MODEL, UI, CAPABILITY }
    enum class UiSetting { CHAR_COUNT, TOKEN_COUNT, MODEL_NAME, TIMESTAMP }
    enum class CapabilitySetting { WEB_SEARCH }

    data class SettingsItem(
        val type: Int,
        val section: Section? = null,
        val modelConfig: ModelConfig? = null,
        val uiSetting: UiSetting? = null,
        val capabilitySetting: CapabilitySetting? = null
    )

    private val expandedSections = mutableSetOf(Section.MODEL)
    private var items = listOf<SettingsItem>()
    private var models = listOf<ModelConfig>()

    var showCharCount = false
    var showTokenCount = false
    var showModelName = false
    var showTimestamp = false
    var webSearchEnabled = false

    fun setModels(newModels: List<ModelConfig>) {
        models = newModels
        rebuildItems()
    }

    fun rebuildItems() {
        val newItems = mutableListOf<SettingsItem>()

        // 模型设置
        newItems.add(SettingsItem(TYPE_HEADER, section = Section.MODEL))
        if (expandedSections.contains(Section.MODEL)) {
            for (model in models) {
                newItems.add(SettingsItem(TYPE_MODEL_ITEM, modelConfig = model))
            }
            newItems.add(SettingsItem(TYPE_ADD_MODEL))
        }

        // 界面设置
        newItems.add(SettingsItem(TYPE_HEADER, section = Section.UI))
        if (expandedSections.contains(Section.UI)) {
            newItems.add(SettingsItem(TYPE_HEADER, section = Section.UI, uiSetting = UiSetting.CHAR_COUNT))
            newItems.add(SettingsItem(TYPE_HEADER, section = Section.UI, uiSetting = UiSetting.TOKEN_COUNT))
            newItems.add(SettingsItem(TYPE_HEADER, section = Section.UI, uiSetting = UiSetting.MODEL_NAME))
            newItems.add(SettingsItem(TYPE_HEADER, section = Section.UI, uiSetting = UiSetting.TIMESTAMP))
        }

        // 能力设置
        newItems.add(SettingsItem(TYPE_HEADER, section = Section.CAPABILITY))
        if (expandedSections.contains(Section.CAPABILITY)) {
            newItems.add(SettingsItem(TYPE_HEADER, section = Section.CAPABILITY, capabilitySetting = CapabilitySetting.WEB_SEARCH))
        }

        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = items[position].type

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_MODEL_ITEM -> ModelViewHolder(ItemModelConfigBinding.inflate(inflater, parent, false))
            TYPE_ADD_MODEL -> AddViewHolder(ItemAddModelBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> bindHeader(holder, item)
            is ModelViewHolder -> bindModel(holder, item)
            is AddViewHolder -> bindAdd(holder)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder, item: SettingsItem) {
        val section = item.section ?: return
        val isExpanded = expandedSections.contains(section)

        holder.binding.tvSectionTitle.text = when (section) {
            Section.MODEL -> holder.itemView.context.getString(R.string.section_model)
            Section.UI -> holder.itemView.context.getString(R.string.section_ui)
            Section.CAPABILITY -> holder.itemView.context.getString(R.string.section_capability)
        }

        holder.binding.ivExpand.animate()
            .rotation(if (isExpanded) 180f else 0f)
            .setDuration(200)
            .start()

        if (item.uiSetting != null || item.capabilitySetting != null) {
            // 子项：显示开关，隐藏箭头
            holder.binding.ivExpand.visibility = View.GONE
            holder.binding.switchSection.visibility = View.VISIBLE

            when (item.uiSetting) {
                UiSetting.CHAR_COUNT -> {
                    holder.binding.tvSectionTitle.text = holder.itemView.context.getString(R.string.ui_show_char_count)
                    holder.binding.switchSection.isChecked = showCharCount
                    holder.binding.switchSection.setOnCheckedChangeListener { _, isChecked ->
                        showCharCount = isChecked
                        onUiSwitchChanged(UiSetting.CHAR_COUNT, isChecked)
                    }
                }
                UiSetting.TOKEN_COUNT -> {
                    holder.binding.tvSectionTitle.text = holder.itemView.context.getString(R.string.ui_show_token_count)
                    holder.binding.switchSection.isChecked = showTokenCount
                    holder.binding.switchSection.setOnCheckedChangeListener { _, isChecked ->
                        showTokenCount = isChecked
                        onUiSwitchChanged(UiSetting.TOKEN_COUNT, isChecked)
                    }
                }
                UiSetting.MODEL_NAME -> {
                    holder.binding.tvSectionTitle.text = holder.itemView.context.getString(R.string.ui_show_model_name)
                    holder.binding.switchSection.isChecked = showModelName
                    holder.binding.switchSection.setOnCheckedChangeListener { _, isChecked ->
                        showModelName = isChecked
                        onUiSwitchChanged(UiSetting.MODEL_NAME, isChecked)
                    }
                }
                UiSetting.TIMESTAMP -> {
                    holder.binding.tvSectionTitle.text = holder.itemView.context.getString(R.string.ui_show_timestamp)
                    holder.binding.switchSection.isChecked = showTimestamp
                    holder.binding.switchSection.setOnCheckedChangeListener { _, isChecked ->
                        showTimestamp = isChecked
                        onUiSwitchChanged(UiSetting.TIMESTAMP, isChecked)
                    }
                }
                null -> {}
            }

            when (item.capabilitySetting) {
                CapabilitySetting.WEB_SEARCH -> {
                    holder.binding.tvSectionTitle.text = holder.itemView.context.getString(R.string.capability_web_search)
                    holder.binding.switchSection.isChecked = webSearchEnabled
                    holder.binding.switchSection.setOnCheckedChangeListener { _, isChecked ->
                        webSearchEnabled = isChecked
                        onCapabilitySwitchChanged(CapabilitySetting.WEB_SEARCH, isChecked)
                    }
                }
                null -> {}
            }

            holder.itemView.setOnClickListener(null)
        } else {
            // 主标题：显示箭头，隐藏开关
            holder.binding.ivExpand.visibility = View.VISIBLE
            holder.binding.switchSection.visibility = View.GONE
            holder.binding.switchSection.setOnCheckedChangeListener(null)

            holder.itemView.setOnClickListener {
                toggleSection(section)
            }
        }
    }

    private fun bindModel(holder: ModelViewHolder, item: SettingsItem) {
        val config = item.modelConfig ?: return
        holder.binding.tvModelTag.text = config.tag
        holder.binding.tvModelUrl.text = config.apiUrl
        holder.binding.chipDefault.visibility = if (config.isDefault) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onModelClick(config) }
        holder.itemView.setOnLongClickListener { view ->
            onModelLongClick(view, config)
            true
        }
    }

    private fun bindAdd(holder: AddViewHolder) {
        holder.itemView.setOnClickListener { onAddModelClick() }
    }

    private fun toggleSection(section: Section) {
        if (expandedSections.contains(section)) {
            expandedSections.remove(section)
        } else {
            expandedSections.add(section)
        }
        rebuildItems()
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ModelViewHolder(val binding: ItemModelConfigBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class AddViewHolder(val binding: ItemAddModelBinding) :
        RecyclerView.ViewHolder(binding.root)
}
