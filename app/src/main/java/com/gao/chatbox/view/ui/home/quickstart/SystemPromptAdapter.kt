package com.gao.chatbox.view.ui.home.quickstart

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt

class SystemPromptAdapter(
    private val onPromptClick: (SystemPrompt) -> Unit,
    private val onAddClick: () -> Unit,
    private val onEditPrompt: (SystemPrompt) -> Unit,
    private val onDeletePrompt: (SystemPrompt) -> Unit
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
                holder.setText(R.id.tv_tag, prompt.tag)
                holder.setText(R.id.tv_content, prompt.content)
                holder.itemView.setOnClickListener { onPromptClick(prompt) }
                if (!prompt.isDefault) {
                    holder.itemView.setOnLongClickListener { view ->
                        showPopupMenu(view, prompt)
                        true
                    }
                } else {
                    holder.itemView.setOnLongClickListener(null)
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

    private fun showPopupMenu(view: View, prompt: SystemPrompt) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.menu_prompt_actions, popup.menu)
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
