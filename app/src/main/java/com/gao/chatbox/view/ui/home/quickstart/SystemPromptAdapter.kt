package com.gao.chatbox.view.ui.home.quickstart

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt

class SystemPromptAdapter(
    private val prompts: List<SystemPrompt>,
    private val onPromptClick: (SystemPrompt) -> Unit,
    private val onAddClick: () -> Unit,
    private val onEditPrompt: (SystemPrompt) -> Unit,
    private val onDeletePrompt: (SystemPrompt) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PROMPT = 0
        private const val TYPE_ADD = 1
    }

    inner class PromptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvPrompt: TextView = itemView.findViewById(R.id.tv_prompt)
    }

    inner class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int {
        return if (position < prompts.size) TYPE_PROMPT else TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_PROMPT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_system_prompt, parent, false)
                PromptViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_add_prompt, parent, false)
                AddViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PromptViewHolder -> {
                val prompt = prompts[position]
                holder.tvPrompt.text = "${prompt.tag}: ${prompt.content}"
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
            is AddViewHolder -> {
                holder.itemView.setOnClickListener { onAddClick() }
            }
        }
    }

    override fun getItemCount(): Int = prompts.size + 1

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
