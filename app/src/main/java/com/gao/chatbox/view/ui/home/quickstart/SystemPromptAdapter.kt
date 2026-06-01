package com.gao.chatbox.view.ui.home.quickstart

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.databinding.ItemAddPromptBinding
import com.gao.chatbox.view.databinding.ItemSystemPromptBinding

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

    inner class PromptViewHolder(val binding: ItemSystemPromptBinding) :
        RecyclerView.ViewHolder(binding.root)

    class AddViewHolder(val binding: ItemAddPromptBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return if (position < prompts.size) TYPE_PROMPT else TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PROMPT -> PromptViewHolder(
                ItemSystemPromptBinding.inflate(inflater, parent, false)
            )
            else -> AddViewHolder(
                ItemAddPromptBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PromptViewHolder -> {
                val prompt = prompts[position]
                holder.binding.tvPrompt.text = "${prompt.tag}: ${prompt.content}"
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
