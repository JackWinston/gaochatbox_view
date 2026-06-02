package com.gao.chatbox.view.ui.home.quickstart

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt

/**
 * 系统提示词列表适配器
 *
 * 使用 BaseMultiItemAdapter 实现多类型 Item 渲染：
 * - TYPE_PROMPT (0): 提示词卡片，显示标签和内容预览
 * - TYPE_ADD (1): 末尾的"+"添加卡片
 *
 * 每个提示词卡片支持：
 * - 点击：启动对应角色的聊天
 * - 长按：弹出编辑/删除菜单（预设提示词只能删除）
 * - 拖拽手柄：触发拖拽排序（默认提示词隐藏手柄）
 */
class SystemPromptAdapter(
    /** 点击提示词卡片的回调，传入被点击的提示词 */
    private val onPromptClick: (SystemPrompt) -> Unit,
    /** 点击末尾"+"卡片的回调 */
    private val onAddClick: () -> Unit,
    /** 长按选择编辑的回调 */
    private val onEditPrompt: (SystemPrompt) -> Unit,
    /** 长按选择删除的回调 */
    private val onDeletePrompt: (SystemPrompt) -> Unit,
    /** 拖拽手柄按下时的回调，传入 adapter position */
    private val onStartDrag: (Int) -> Unit
) : BaseMultiItemAdapter<SystemPromptAdapter.Item>() {

    companion object {
        /** 提示词卡片类型 */
        const val TYPE_PROMPT = 0
        /** 添加按钮卡片类型 */
        const val TYPE_ADD = 1

        /**
         * 将提示词列表转换为适配器 Item 列表
         * 在末尾追加一个 Add 类型的 Item 作为"新增"按钮
         */
        fun buildItems(prompts: List<SystemPrompt>): List<Item> {
            val items = prompts.map { Item.Prompt(it) }.toMutableList<Item>()
            items.add(Item.Add())
            return items
        }
    }

    /** 密封类定义两种 Item 类型 */
    sealed class Item {
        /** 提示词卡片 Item，包装了 SystemPrompt 数据 */
        class Prompt(val prompt: SystemPrompt) : Item()
        /** 添加按钮 Item，无数据字段 */
        class Add : Item()
    }

    init {
        // 根据 Item 类型返回对应的 viewType 常量
        onItemViewType { position, list ->
            when (list[position]) {
                is Item.Prompt -> TYPE_PROMPT
                is Item.Add -> TYPE_ADD
            }
        }

        // ========== 提示词卡片类型 ==========
        addItemType(TYPE_PROMPT, object : OnMultiItem<Item, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_system_prompt, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: Item?) {
                val prompt = (item as? Item.Prompt)?.prompt ?: return
                val dragHandle = holder.getView<ImageView>(R.id.iv_drag_handle)
                val statusPin = holder.getView<ImageView>(R.id.iv_status_pin)

                // 设置标签和内容预览文本
                holder.setText(R.id.tv_tag, prompt.tag)
                holder.setText(R.id.tv_content, prompt.content)
                holder.itemView.setOnClickListener { onPromptClick(prompt) }

                if (prompt.isDefault) {
                    // 默认提示词：显示固定图钉，隐藏拖拽手柄，禁用长按菜单
                    statusPin.visibility = View.VISIBLE
                    dragHandle.visibility = View.GONE
                    dragHandle.setOnTouchListener(null)
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // 自定义提示词：隐藏图钉，显示拖拽手柄
                    statusPin.visibility = View.GONE
                    dragHandle.visibility = View.VISIBLE
                    // 拖拽手柄的触摸监听：ACTION_DOWN 时触发 ItemTouchHelper 开始拖拽
                    dragHandle.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val adapterPosition = holder.adapterPosition
                            if (adapterPosition != -1) {
                                onStartDrag(adapterPosition)
                            }
                        }
                        false
                    }
                    // 长按弹出编辑/删除菜单（预设提示词不允许编辑）
                    holder.itemView.setOnLongClickListener { view ->
                        showPopupMenu(view, prompt, allowEdit = !prompt.isPreset)
                        true
                    }
                }
            }
        })

        // ========== 添加按钮卡片类型 ==========
        addItemType(TYPE_ADD, object : OnMultiItem<Item, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_add_prompt, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: Item?) {
                holder.itemView.setOnClickListener { onAddClick() }
            }
        })
    }

    /**
     * 显示提示词卡片的长按弹出菜单
     *
     * @param view 锚点 View，菜单显示在其附近
     * @param prompt 当前提示词
     * @param allowEdit 是否允许编辑（预设提示词 isPreset=true 时不允许编辑，只显示删除）
     */
    private fun showPopupMenu(view: View, prompt: SystemPrompt, allowEdit: Boolean) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.menu_prompt_actions, popup.menu)
        if (!allowEdit) {
            popup.menu.removeItem(R.id.action_edit)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    onEditPrompt(prompt)
                    true
                }
                R.id.action_delete -> {
                    onDeletePrompt(prompt)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
