package com.gao.chatbox.view.ui.home.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import com.gao.chatbox.view.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onItemClick: (ConversationWithLastMessage) -> Unit,
    private val onItemLongClick: (ConversationWithLastMessage) -> Unit
) : ListAdapter<ConversationWithLastMessage, ConversationAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConversationWithLastMessage>() {
            override fun areItemsTheSame(
                oldItem: ConversationWithLastMessage,
                newItem: ConversationWithLastMessage
            ): Boolean = oldItem.conversation.id == newItem.conversation.id

            override fun areContentsTheSame(
                oldItem: ConversationWithLastMessage,
                newItem: ConversationWithLastMessage
            ): Boolean = oldItem == newItem
        }

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(pos))
                }
            }
            binding.root.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(pos))
                }
                true
            }
        }

        fun bind(item: ConversationWithLastMessage) {
            binding.tvDisplayTag.text = item.conversation.displayTag ?: item.conversation.title
            binding.tvLastMessage.text = item.lastMessage ?: ""
            binding.tvTimestamp.text = item.conversation.updatedAt.formatRelative()
        }
    }

    private fun Long.formatRelative(): String {
        val now = System.currentTimeMillis()
        val date = Date(this)
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return when {
            this >= todayStart -> timeFormat.format(date)
            this >= todayStart - 86400000 -> "昨天"
            this >= todayStart - 2 * 86400000 -> "前天"
            else -> dateFormat.format(date)
        }
    }
}
