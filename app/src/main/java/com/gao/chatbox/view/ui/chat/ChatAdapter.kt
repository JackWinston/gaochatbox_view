package com.gao.chatbox.view.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.databinding.ItemChatMessageAssistantBinding
import com.gao.chatbox.view.databinding.ItemChatMessageUserBinding
import com.gao.chatbox.view.databinding.ItemChatStreamingBinding
import com.gao.chatbox.view.databinding.ItemChatSystemPromptBinding
import com.gao.chatbox.view.databinding.ItemChatTimestampBinding
import io.noties.markwon.Markwon

class ChatAdapter(
    private val markwon: Markwon,
    private val listener: ChatAdapterListener? = null
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(ChatItemDiffCallback()) {

    companion object {
        const val TYPE_TIMESTAMP = 0
        const val TYPE_SYSTEM_PROMPT = 1
        const val TYPE_USER = 2
        const val TYPE_ASSISTANT = 3
        const val TYPE_STREAMING = 4
    }

    interface ChatAdapterListener {
        fun onSystemPromptToggle(position: Int)
        fun onContentLongPress(content: String)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChatItem.Timestamp -> TYPE_TIMESTAMP
        is ChatItem.SystemPrompt -> TYPE_SYSTEM_PROMPT
        is ChatItem.UserMessage -> TYPE_USER
        is ChatItem.AssistantMessage -> TYPE_ASSISTANT
        is ChatItem.StreamingMessage -> TYPE_STREAMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TIMESTAMP -> TimestampViewHolder(
                ItemChatTimestampBinding.inflate(inflater, parent, false)
            )
            TYPE_SYSTEM_PROMPT -> SystemPromptViewHolder(
                ItemChatSystemPromptBinding.inflate(inflater, parent, false)
            )
            TYPE_USER -> UserMessageViewHolder(
                ItemChatMessageUserBinding.inflate(inflater, parent, false)
            )
            TYPE_ASSISTANT -> AssistantMessageViewHolder(
                ItemChatMessageAssistantBinding.inflate(inflater, parent, false)
            )
            TYPE_STREAMING -> StreamingViewHolder(
                ItemChatStreamingBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.Timestamp -> (holder as TimestampViewHolder).bind(item)
            is ChatItem.SystemPrompt -> (holder as SystemPromptViewHolder).bind(item)
            is ChatItem.UserMessage -> (holder as UserMessageViewHolder).bind(item)
            is ChatItem.AssistantMessage -> (holder as AssistantMessageViewHolder).bind(item)
            is ChatItem.StreamingMessage -> (holder as StreamingViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        if (payloads.isNotEmpty() && holder is SystemPromptViewHolder) {
            holder.toggleExpand()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is AssistantMessageViewHolder -> holder.binding.tvContent.text = null
            is StreamingViewHolder -> {
                holder.binding.tvContent.text = null
                holder.streamingContent = ""
                holder.isExpanded = false
            }
        }
    }

    fun findStreamingViewHolder(recyclerView: RecyclerView): StreamingViewHolder? {
        for (i in 0 until itemCount) {
            if (getItem(i) is ChatItem.StreamingMessage) {
                val holder = recyclerView.findViewHolderForAdapterPosition(i)
                if (holder is StreamingViewHolder) return holder
            }
        }
        return null
    }

    // ==================== ViewHolders ====================

    class TimestampViewHolder(val binding: ItemChatTimestampBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Timestamp) {
            binding.tvTimestamp.text = item.timeText
        }
    }

    inner class SystemPromptViewHolder(val binding: ItemChatSystemPromptBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var isExpanded = false
        private val collapsedMaxLines = 5

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = getItem(pos) as? ChatItem.SystemPrompt ?: return@setOnClickListener
                    if (binding.tvContent.lineCount > collapsedMaxLines) {
                        listener?.onSystemPromptToggle(pos)
                    }
                }
            }
        }

        fun bind(item: ChatItem.SystemPrompt) {
            isExpanded = item.isExpanded
            binding.tvContent.text = item.content
            applyExpandState()
        }

        fun toggleExpand() {
            isExpanded = !isExpanded
            applyExpandState()
        }

        private fun applyExpandState() {
            if (isExpanded) {
                binding.tvContent.maxLines = Int.MAX_VALUE
                binding.tvContent.ellipsize = null
                binding.ivExpandIcon.setImageResource(R.drawable.ic_expand_less)
                binding.ivExpandIcon.visibility = View.VISIBLE
            } else {
                binding.tvContent.maxLines = collapsedMaxLines
                binding.tvContent.ellipsize = android.text.TextUtils.TruncateAt.END
                binding.ivExpandIcon.setImageResource(R.drawable.ic_expand_more)
                binding.tvContent.post {
                    binding.ivExpandIcon.visibility =
                        if (binding.tvContent.lineCount > collapsedMaxLines) View.VISIBLE else View.GONE
                }
            }
        }
    }

    class UserMessageViewHolder(val binding: ItemChatMessageUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.UserMessage) {
            binding.tvContent.text = item.content
            if (item.attachmentName != null) {
                binding.dividerAttachment.visibility = View.VISIBLE
                binding.layoutAttachment.visibility = View.VISIBLE
                binding.tvAttachmentName.text = item.attachmentName
            } else {
                binding.dividerAttachment.visibility = View.GONE
                binding.layoutAttachment.visibility = View.GONE
            }
        }
    }

    inner class AssistantMessageViewHolder(val binding: ItemChatMessageAssistantBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.tvContent.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = getItem(pos) as? ChatItem.AssistantMessage
                    item?.let { listener?.onContentLongPress(it.content) }
                }
                true
            }
        }

        fun bind(item: ChatItem.AssistantMessage) {
            markwon.setMarkdown(binding.tvContent, item.content)
            if (item.modelName != null) {
                binding.tvModelName.text = item.modelName
                binding.tvModelName.visibility = View.VISIBLE
            } else {
                binding.tvModelName.visibility = View.GONE
            }
        }
    }

    inner class StreamingViewHolder(val binding: ItemChatStreamingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var streamingContent: String = ""
        var isExpanded: Boolean = false

        init {
            binding.layoutHeader.setOnClickListener {
                toggleExpand()
            }
        }

        fun bind(item: ChatItem.StreamingMessage) {
            val content = streamingContent.ifEmpty { item.content }
            if (item.isThinking && content.isEmpty()) {
                binding.progressThinking.visibility = View.VISIBLE
                binding.tvStatus.text = itemView.context.getString(R.string.chat_thinking)
            } else {
                binding.progressThinking.visibility = View.GONE
                binding.tvStatus.text = itemView.context.getString(R.string.chat_streaming_done)
            }
            applyExpandState(content)
        }

        fun updateStreamingContent(content: String) {
            streamingContent = content
            binding.progressThinking.visibility = View.GONE
            binding.tvStatus.text = itemView.context.getString(R.string.chat_streaming_responding)
            if (isExpanded) {
                binding.tvContent.text = content
            }
        }

        private fun toggleExpand() {
            isExpanded = !isExpanded
            applyExpandState(streamingContent)
            if (isExpanded) {
                binding.tvContent.post {
                    val parent = itemView.parent
                    if (parent is RecyclerView) {
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            parent.smoothScrollToPosition(pos)
                        }
                    }
                }
            }
        }

        private fun applyExpandState(content: String) {
            if (isExpanded) {
                binding.ivExpand.setImageResource(R.drawable.ic_expand_less)
                binding.tvContent.visibility = View.VISIBLE
                binding.tvContent.text = content
            } else {
                binding.ivExpand.setImageResource(R.drawable.ic_expand_more)
                binding.tvContent.visibility = View.GONE
            }
        }
    }

    // ==================== DiffUtil ====================

    private class ChatItemDiffCallback : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
            if (oldItem::class != newItem::class) return false
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ChatItem, newItem: ChatItem): Any? {
            if (oldItem is ChatItem.SystemPrompt && newItem is ChatItem.SystemPrompt
                && oldItem.content == newItem.content
                && oldItem.isExpanded != newItem.isExpanded
            ) {
                return "toggle_expand"
            }
            return null
        }
    }
}
