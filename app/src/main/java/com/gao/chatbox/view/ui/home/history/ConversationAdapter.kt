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

/**
 * 对话列表适配器
 *
 * 使用 ListAdapter + DiffUtil 实现高效的列表差异更新。
 * 每个对话项显示：标题（displayTag）、最后一条消息预览、相对时间戳。
 *
 * DiffUtil 通过比较 conversation.id 判断是否同一项，
 * 通过比较整个对象判断内容是否变化，实现局部刷新。
 */
class ConversationAdapter(
    /** 点击对话项的回调 */
    private val onItemClick: (ConversationWithLastMessage) -> Unit,
    /** 长按对话项的回调 */
    private val onItemLongClick: (ConversationWithLastMessage) -> Unit
) : ListAdapter<ConversationWithLastMessage, ConversationAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        /** DiffUtil 回调，用于计算列表差异 */
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConversationWithLastMessage>() {
            /** 判断是否同一项：比较对话 ID */
            override fun areItemsTheSame(
                oldItem: ConversationWithLastMessage,
                newItem: ConversationWithLastMessage
            ): Boolean = oldItem.conversation.id == newItem.conversation.id

            /** 判断内容是否变化：比较整个对象（包括最后消息、时间戳等） */
            override fun areContentsTheSame(
                oldItem: ConversationWithLastMessage,
                newItem: ConversationWithLastMessage
            ): Boolean = oldItem == newItem
        }

        /** 时间格式：仅时分（今天的消息） */
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        /** 日期格式：月/日 时分（更早的消息） */
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
            // 优先显示 displayTag，回退到 title
            binding.tvDisplayTag.text = item.conversation.displayTag ?: item.conversation.title
            binding.tvLastMessage.text = item.lastMessage ?: ""
            binding.tvTimestamp.text = item.conversation.updatedAt.formatRelative()
        }
    }

    /**
     * 将时间戳格式化为相对时间字符串
     *
     * 规则：
     * - 今天 → 显示 "HH:mm"
     * - 昨天 → 显示 "昨天"
     * - 前天 → 显示 "前天"
     * - 更早 → 显示 "M/d HH:mm"
     */
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
