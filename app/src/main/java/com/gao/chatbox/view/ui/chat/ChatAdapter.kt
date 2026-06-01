package com.gao.chatbox.view.ui.chat

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
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
                inflater.inflate(R.layout.item_chat_timestamp, parent, false)
            )
            TYPE_SYSTEM_PROMPT -> SystemPromptViewHolder(
                inflater.inflate(R.layout.item_chat_system_prompt, parent, false)
            )
            TYPE_USER -> UserMessageViewHolder(
                inflater.inflate(R.layout.item_chat_message_user, parent, false)
            )
            TYPE_ASSISTANT -> AssistantMessageViewHolder(
                inflater.inflate(R.layout.item_chat_message_assistant, parent, false)
            )
            TYPE_STREAMING -> StreamingViewHolder(
                inflater.inflate(R.layout.item_chat_streaming, parent, false)
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
            is AssistantMessageViewHolder -> holder.tvContent.text = null
            is StreamingViewHolder -> {
                holder.tvContent.text = null
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

    class TimestampViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)

        fun bind(item: ChatItem.Timestamp) {
            tvTimestamp.text = item.timeText
        }
    }

    inner class SystemPromptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val ivExpandIcon: ImageView = itemView.findViewById(R.id.iv_expand_icon)
        private var isExpanded = false
        private val collapsedMaxLines = 5

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = getItem(pos) as? ChatItem.SystemPrompt ?: return@setOnClickListener
                    if (tvContent.lineCount > collapsedMaxLines) {
                        listener?.onSystemPromptToggle(pos)
                    }
                }
            }
        }

        fun bind(item: ChatItem.SystemPrompt) {
            isExpanded = item.isExpanded
            tvContent.text = item.content
            applyExpandState()
        }

        fun toggleExpand() {
            isExpanded = !isExpanded
            applyExpandState()
        }

        private fun applyExpandState() {
            if (isExpanded) {
                tvContent.maxLines = Int.MAX_VALUE
                tvContent.ellipsize = null
                ivExpandIcon.setImageResource(R.drawable.ic_expand_less)
                ivExpandIcon.visibility = View.VISIBLE
            } else {
                tvContent.maxLines = collapsedMaxLines
                tvContent.ellipsize = android.text.TextUtils.TruncateAt.END
                ivExpandIcon.setImageResource(R.drawable.ic_expand_more)
                tvContent.post {
                    ivExpandIcon.visibility = if (tvContent.lineCount > collapsedMaxLines) View.VISIBLE else View.GONE
                }
            }
        }
    }

    class UserMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val dividerAttachment: View = itemView.findViewById(R.id.divider_attachment)
        private val layoutAttachment: LinearLayout = itemView.findViewById(R.id.layout_attachment)
        private val tvAttachmentName: TextView = itemView.findViewById(R.id.tv_attachment_name)

        fun bind(item: ChatItem.UserMessage) {
            tvContent.text = item.content
            if (item.attachmentName != null) {
                dividerAttachment.visibility = View.VISIBLE
                layoutAttachment.visibility = View.VISIBLE
                tvAttachmentName.text = item.attachmentName
            } else {
                dividerAttachment.visibility = View.GONE
                layoutAttachment.visibility = View.GONE
            }
        }
    }

    inner class AssistantMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val tvModelName: TextView = itemView.findViewById(R.id.tv_model_name)

        init {
            tvContent.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = getItem(pos) as? ChatItem.AssistantMessage
                    item?.let { listener?.onContentLongPress(it.content) }
                }
                true
            }
        }

        fun bind(item: ChatItem.AssistantMessage) {
            markwon.setMarkdown(tvContent, item.content)
            if (item.modelName != null) {
                tvModelName.text = item.modelName
                tvModelName.visibility = View.VISIBLE
            } else {
                tvModelName.visibility = View.GONE
            }
        }
    }

    inner class StreamingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val progressThinking: ProgressBar = itemView.findViewById(R.id.progress_thinking)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
        private val ivExpand: ImageView = itemView.findViewById(R.id.iv_expand)
        val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        var streamingContent: String = ""
        var isExpanded: Boolean = false

        init {
            itemView.findViewById<View>(R.id.layout_header).setOnClickListener {
                toggleExpand()
            }
        }

        fun bind(item: ChatItem.StreamingMessage) {
            val content = streamingContent.ifEmpty { item.content }
            if (item.isThinking && content.isEmpty()) {
                progressThinking.visibility = View.VISIBLE
                tvStatus.text = itemView.context.getString(R.string.chat_thinking)
            } else {
                progressThinking.visibility = View.GONE
                tvStatus.text = itemView.context.getString(R.string.chat_streaming_done)
            }
            applyExpandState(content)
        }

        fun updateStreamingContent(content: String) {
            streamingContent = content
            progressThinking.visibility = View.GONE
            tvStatus.text = itemView.context.getString(R.string.chat_streaming_responding)
            if (isExpanded) {
                tvContent.text = content
            }
        }

        private fun toggleExpand() {
            isExpanded = !isExpanded
            applyExpandState(streamingContent)
            if (isExpanded) {
                tvContent.post {
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
                ivExpand.setImageResource(R.drawable.ic_expand_less)
                tvContent.visibility = View.VISIBLE
                tvContent.text = content
            } else {
                ivExpand.setImageResource(R.drawable.ic_expand_more)
                tvContent.visibility = View.GONE
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
