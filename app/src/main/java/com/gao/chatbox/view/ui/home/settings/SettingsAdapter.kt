package com.gao.chatbox.view.ui.home.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.ModelConfig
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 设置页面列表适配器
 *
 * 使用 BaseMultiItemAdapter 实现多类型 Item 渲染，支持三种分组：
 * - MODEL: 模型配置列表 + 添加按钮
 * - UI: 主题、语言、字符数/Token数/模型名/时间戳显示开关
 * - CAPABILITY: 网页搜索开关、最大工具调用轮次设置
 *
 * 每个分组支持折叠/展开，通过 expandedSections 集合管理展开状态。
 * 复用 item_section_header 布局渲染 Header、UiSwitch、CapabilitySwitch、CapabilityAction 四种类型。
 */
class SettingsAdapter(
    /** 点击模型项的回调 */
    private val onModelClick: (ModelConfig) -> Unit,
    /** 长按模型项的回调 */
    private val onModelLongClick: (View, ModelConfig) -> Unit,
    /** 点击"添加模型"按钮的回调 */
    private val onAddModelClick: () -> Unit,
    /** UI 开关变化的回调 */
    private val onUiSwitchChanged: (UiSetting, Boolean) -> Unit,
    /** 能力开关变化的回调 */
    private val onCapabilitySwitchChanged: (CapabilitySetting, Boolean) -> Unit,
    /** 点击最大工具调用轮次的回调 */
    private val onMaxToolCallRoundsClick: () -> Unit,
    /** 点击语言设置的回调 */
    private val onLanguageClick: () -> Unit,
    /** 点击主题设置的回调 */
    private val onThemeClick: () -> Unit
) : BaseMultiItemAdapter<SettingsAdapter.SettingsItem>() {

    companion object {
        /** 分组头部类型（也用于 UiSwitch、CapabilitySwitch、CapabilityAction） */
        const val TYPE_HEADER = 0
        /** 模型配置项类型 */
        const val TYPE_MODEL_ITEM = 1
        /** 添加模型按钮类型 */
        const val TYPE_ADD_MODEL = 2
    }

    /** 设置分组枚举 */
    enum class Section { MODEL, UI, CAPABILITY }

    /** UI 设置项枚举 */
    enum class UiSetting { CHAR_COUNT, TOKEN_COUNT, MODEL_NAME, TIMESTAMP, LANGUAGE, THEME }

    /** 能力设置项枚举 */
    enum class CapabilitySetting { WEB_SEARCH, MAX_TOOL_CALL_ROUNDS }

    /** 密封类定义所有可能的设置 Item 类型 */
    sealed class SettingsItem {
        /** 分组头部（可折叠/展开） */
        class Header(val section: Section) : SettingsItem()
        /** 模型配置项 */
        class Model(val config: ModelConfig) : SettingsItem()
        /** 添加模型按钮 */
        class AddModel : SettingsItem()
        /** UI 开关设置项 */
        class UiSwitch(val setting: UiSetting) : SettingsItem()
        /** 能力开关设置项 */
        class CapabilitySwitch(val setting: CapabilitySetting) : SettingsItem()
        /** 能力操作设置项（如点击弹出对话框） */
        class CapabilityAction(val setting: CapabilitySetting) : SettingsItem()
    }

    /** 当前展开的分组集合，默认展开 MODEL 分组 */
    private val expandedSections = mutableSetOf(Section.MODEL)

    /** 模型配置列表缓存 */
    private var models = listOf<ModelConfig>()

    // UI 偏好设置状态（由 Fragment 从 ViewModel 收集后设置）
    var showCharCount = false
    var showTokenCount = false
    var showModelName = false
    var showTimestamp = false

    // 能力设置状态
    var webSearchEnabled = false
    var maxToolCallRounds = 8

    // 语言和主题设置状态
    var currentLanguage = "system"
    var currentTheme = "system"

    init {
        onItemViewType { position, list ->
            when (list[position]) {
                is SettingsItem.Header -> TYPE_HEADER
                is SettingsItem.Model -> TYPE_MODEL_ITEM
                is SettingsItem.AddModel -> TYPE_ADD_MODEL
                is SettingsItem.UiSwitch -> TYPE_HEADER
                is SettingsItem.CapabilitySwitch -> TYPE_HEADER
                is SettingsItem.CapabilityAction -> TYPE_HEADER
            }
        }

        addItemType(TYPE_HEADER, object : OnMultiItem<SettingsItem, QuickViewHolder>() {
            override fun onCreate(
                context: Context,
                parent: ViewGroup,
                viewType: Int
            ): QuickViewHolder {
                return QuickViewHolder(R.layout.item_section_header, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: SettingsItem?) {
                when (item) {
                    is SettingsItem.Header -> bindHeader(holder, item)
                    is SettingsItem.UiSwitch -> bindUiSwitch(holder, item)
                    is SettingsItem.CapabilitySwitch -> bindCapabilitySwitch(holder, item)
                    is SettingsItem.CapabilityAction -> bindCapabilityAction(holder, item)
                    else -> {}
                }
            }
        })

        addItemType(TYPE_MODEL_ITEM, object : OnMultiItem<SettingsItem, QuickViewHolder>() {
            override fun onCreate(
                context: Context,
                parent: ViewGroup,
                viewType: Int
            ): QuickViewHolder {
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
            override fun onCreate(
                context: Context,
                parent: ViewGroup,
                viewType: Int
            ): QuickViewHolder {
                return QuickViewHolder(R.layout.item_add_model, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: SettingsItem?) {
                holder.itemView.setOnClickListener { onAddModelClick() }
            }
        })
    }

    /**
     * 绑定分组头部
     *
     * 显示分组标题和展开/收起箭头图标。
     * 点击头部切换展开状态，箭头带有旋转动画。
     */
    private fun bindHeader(holder: QuickViewHolder, item: SettingsItem.Header) {
        val section = item.section
        val isExpanded = expandedSections.contains(section)

        holder.setText(
            R.id.tv_section_title, when (section) {
                Section.MODEL -> holder.itemView.context.getString(R.string.section_model)
                Section.UI -> holder.itemView.context.getString(R.string.section_ui)
                Section.CAPABILITY -> holder.itemView.context.getString(R.string.section_capability)
            }
        )

        // 展开箭头旋转动画（展开时旋转 180 度）
        holder.getView<View>(R.id.iv_expand).animate()
            .rotation(if (isExpanded) 180f else 0f)
            .setDuration(200)
            .start()

        holder.setVisible(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, true)
        holder.itemView.setOnClickListener { toggleSection(section) }
    }

    /**
     * 绑定 UI 开关设置项
     *
     * 根据 setting 类型显示不同的标题和开关状态。
     * LANGUAGE 和 THEME 特殊处理：显示当前值文本，点击弹出选择对话框。
     * 其他项显示 Switch 开关，切换时通过回调通知 Fragment。
     *
     * 注意：设置 OnCheckedChangeListener 前先置空，避免复用 ViewHolder 时触发旧回调。
     */
    private fun bindUiSwitch(holder: QuickViewHolder, item: SettingsItem.UiSwitch) {
        holder.setGone(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, false)

        val switchView = holder.getView<MaterialSwitch>(R.id.switch_section)
        switchView.setOnCheckedChangeListener(null)

        when (item.setting) {
            UiSetting.CHAR_COUNT -> {
                holder.setText(
                    R.id.tv_section_title,
                    holder.itemView.context.getString(R.string.ui_show_char_count)
                )
                switchView.isChecked = showCharCount
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showCharCount = isChecked
                    onUiSwitchChanged(UiSetting.CHAR_COUNT, isChecked)
                }
            }

            UiSetting.TOKEN_COUNT -> {
                holder.setText(
                    R.id.tv_section_title,
                    holder.itemView.context.getString(R.string.ui_show_token_count)
                )
                switchView.isChecked = showTokenCount
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showTokenCount = isChecked
                    onUiSwitchChanged(UiSetting.TOKEN_COUNT, isChecked)
                }
            }

            UiSetting.MODEL_NAME -> {
                holder.setText(
                    R.id.tv_section_title,
                    holder.itemView.context.getString(R.string.ui_show_model_name)
                )
                switchView.isChecked = showModelName
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showModelName = isChecked
                    onUiSwitchChanged(UiSetting.MODEL_NAME, isChecked)
                }
            }

            UiSetting.TIMESTAMP -> {
                holder.setText(
                    R.id.tv_section_title,
                    holder.itemView.context.getString(R.string.ui_show_timestamp)
                )
                switchView.isChecked = showTimestamp
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    showTimestamp = isChecked
                    onUiSwitchChanged(UiSetting.TIMESTAMP, isChecked)
                }
            }

            // 语言设置：显示当前语言名称，点击弹出选择对话框
            UiSetting.LANGUAGE -> {
                val context = holder.itemView.context
                val languageName = when (currentLanguage) {
                    "zh" -> context.getString(R.string.language_chinese)
                    "en" -> context.getString(R.string.language_english)
                    else -> context.getString(R.string.language_system)
                }
                holder.setText(
                    R.id.tv_section_title,
                    "${context.getString(R.string.ui_language)}: $languageName"
                )
                holder.setGone(R.id.iv_expand, true)
                holder.setGone(R.id.switch_section, true)
                holder.itemView.setOnClickListener { onLanguageClick() }
            }

            // 主题设置：显示当前主题名称，点击弹出选择对话框
            UiSetting.THEME -> {
                val context = holder.itemView.context
                val themeName = when (currentTheme) {
                    "light" -> context.getString(R.string.theme_light)
                    "dark" -> context.getString(R.string.theme_dark)
                    else -> context.getString(R.string.theme_system)
                }
                holder.setText(
                    R.id.tv_section_title,
                    "${context.getString(R.string.ui_theme)}: $themeName"
                )
                holder.setGone(R.id.iv_expand, true)
                holder.setGone(R.id.switch_section, true)
                holder.itemView.setOnClickListener { onThemeClick() }
            }
        }

        // 非 LANGUAGE/THEME 项清除点击监听（开关由 Switch 控制）
        if (item.setting != UiSetting.LANGUAGE && item.setting != UiSetting.THEME) {
            holder.itemView.setOnClickListener(null)
        }
    }

    /**
     * 绑定能力开关设置项（如网页搜索）
     * 使用 Switch 控制开关状态
     */
    private fun bindCapabilitySwitch(holder: QuickViewHolder, item: SettingsItem.CapabilitySwitch) {
        holder.setGone(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, false)

        val switchView = holder.getView<MaterialSwitch>(R.id.switch_section)
        switchView.setOnCheckedChangeListener(null)

        when (item.setting) {
            CapabilitySetting.WEB_SEARCH -> {
                holder.setText(
                    R.id.tv_section_title,
                    holder.itemView.context.getString(R.string.capability_web_search)
                )
                switchView.isChecked = webSearchEnabled
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    webSearchEnabled = isChecked
                    onCapabilitySwitchChanged(CapabilitySetting.WEB_SEARCH, isChecked)
                }
            }

            else -> {}
        }

        holder.itemView.setOnClickListener(null)
    }

    /**
     * 绑定能力操作设置项（如最大工具调用轮次）
     * 点击整行弹出数值输入对话框
     */
    private fun bindCapabilityAction(holder: QuickViewHolder, item: SettingsItem.CapabilityAction) {
        holder.setGone(R.id.iv_expand, true)
        holder.setGone(R.id.switch_section, true)

        when (item.setting) {
            CapabilitySetting.MAX_TOOL_CALL_ROUNDS -> {
                val context = holder.itemView.context
                holder.setText(
                    R.id.tv_section_title,
                    context.getString(
                        R.string.capability_max_tool_call_rounds_value,
                        maxToolCallRounds
                    )
                )
                holder.itemView.setOnClickListener { onMaxToolCallRoundsClick() }
            }

            else -> holder.itemView.setOnClickListener(null)
        }
    }

    /** 更新模型列表并重建 Item 列表 */
    fun setModels(newModels: List<ModelConfig>) {
        models = newModels
        rebuildItems()
    }

    /**
     * 重建整个设置列表的 Item 数据
     *
     * 按分组顺序生成 Item 列表：
     * 1. MODEL 分组：Header + 模型列表 + 添加按钮
     * 2. UI 分组：Header + 主题/语言/各显示开关
     * 3. CAPABILITY 分组：Header + 网页搜索开关 + 工具调用轮次
     *
     * 每个分组只在展开状态下才添加子项。
     * 最终通过 submitList 提交给 BaseMultiItemAdapter 渲染。
     */
    fun rebuildItems() {
        val newItems = mutableListOf<SettingsItem>()

        // 模型设置分组
        newItems.add(SettingsItem.Header(Section.MODEL))
        if (expandedSections.contains(Section.MODEL)) {
            for (model in models) {
                newItems.add(SettingsItem.Model(model))
            }
            newItems.add(SettingsItem.AddModel())
        }

        // 界面设置分组
        newItems.add(SettingsItem.Header(Section.UI))
        if (expandedSections.contains(Section.UI)) {
            newItems.add(SettingsItem.UiSwitch(UiSetting.THEME))
            newItems.add(SettingsItem.UiSwitch(UiSetting.LANGUAGE))
            newItems.add(SettingsItem.UiSwitch(UiSetting.CHAR_COUNT))
            newItems.add(SettingsItem.UiSwitch(UiSetting.TOKEN_COUNT))
            newItems.add(SettingsItem.UiSwitch(UiSetting.MODEL_NAME))
            newItems.add(SettingsItem.UiSwitch(UiSetting.TIMESTAMP))
        }

        // 能力设置分组
        newItems.add(SettingsItem.Header(Section.CAPABILITY))
        if (expandedSections.contains(Section.CAPABILITY)) {
            newItems.add(SettingsItem.CapabilitySwitch(CapabilitySetting.WEB_SEARCH))
            newItems.add(SettingsItem.CapabilityAction(CapabilitySetting.MAX_TOOL_CALL_ROUNDS))
        }

        submitList(newItems)
    }

    /**
     * 切换分组的展开/收起状态
     * 切换后自动重建列表
     */
    private fun toggleSection(section: Section) {
        if (expandedSections.contains(section)) {
            expandedSections.remove(section)
        } else {
            expandedSections.add(section)
        }
        rebuildItems()
    }
}
