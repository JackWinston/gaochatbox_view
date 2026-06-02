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

class SystemPromptAdapter(
    private val onPromptClick: (SystemPrompt) -> Unit,
    private val onAddClick: () -> Unit,
    private val onEditPrompt: (SystemPrompt) -> Unit,
    private val onDeletePrompt: (SystemPrompt) -> Unit,
    private val onStartDrag: (Int) -> Unit
) : BaseMultiItemAdapter<SystemPromptAdapter.Item>() {

    companion object {
        const val TYPE_PROMPT = 0
        const val TYPE_ADD = 1

        fun buildItems(prompts: List<SystemPrompt>): List<Item> {
            val items = prompts.map { Item.Prompt(it) }.toMutableList<Item>()
            items.add(Item.Add())
            return items
        }
    }

    sealed class Item {
        class Prompt(val prompt: SystemPrompt) : Item()
        class Add : Item()
    }

    init {
        onItemViewType { position, list ->
            when (list[position]) {
                is Item.Prompt -> TYPE_PROMPT
                is Item.Add -> TYPE_ADD
            }
        }

        addItemType(TYPE_PROMPT, object : OnMultiItem<Item, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_system_prompt, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: Item?) {
                val prompt = (item as? Item.Prompt)?.prompt ?: return
                val dragHandle = holder.getView<ImageView>(R.id.iv_drag_handle)
                val statusPin = holder.getView<ImageView>(R.id.iv_status_pin)
                holder.setText(R.id.tv_tag, prompt.tag)
                holder.setText(R.id.tv_content, prompt.content)
                holder.itemView.setOnClickListener { onPromptClick(prompt) }
                if (prompt.isDefault) {
                    statusPin.visibility = View.VISIBLE
                    dragHandle.visibility = View.GONE
                    dragHandle.setOnTouchListener(null)
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    statusPin.visibility = View.GONE
                    dragHandle.visibility = View.VISIBLE
                    dragHandle.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val adapterPosition = holder.adapterPosition
                            if (adapterPosition != -1) {
                                onStartDrag(adapterPosition)
                            }
                        }
                        false
                    }
                    holder.itemView.setOnLongClickListener { view ->
                        showPopupMenu(view, prompt, allowEdit = !prompt.isPreset)
                        true
                    }
                }
            }
        })

        addItemType(TYPE_ADD, object : OnMultiItem<Item, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_add_prompt, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: Item?) {
                holder.itemView.setOnClickListener { onAddClick() }
            }
        })
    }

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
