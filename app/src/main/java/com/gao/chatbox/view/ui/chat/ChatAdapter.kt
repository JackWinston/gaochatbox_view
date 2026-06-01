package com.gao.chatbox.view.ui.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import io.noties.markwon.Markwon

class ChatAdapter(
    private val markwon: Markwon,
    private val listener: ChatAdapterListener? = null
) : BaseMultiItemAdapter<ChatItem>() {

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

    init {
        onItemViewType { position, list ->
            when (list[position]) {
                is ChatItem.Timestamp -> TYPE_TIMESTAMP
                is ChatItem.SystemPrompt -> TYPE_SYSTEM_PROMPT
                is ChatItem.UserMessage -> TYPE_USER
                is ChatItem.AssistantMessage -> TYPE_ASSISTANT
                is ChatItem.StreamingMessage -> TYPE_STREAMING
            }
        }

        addItemType(TYPE_TIMESTAMP, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_timestamp, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val timestamp = item as? ChatItem.Timestamp ?: return
                holder.setText(R.id.tv_timestamp, timestamp.timeText)
            }
        })

        addItemType(TYPE_SYSTEM_PROMPT, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_system_prompt, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val prompt = item as? ChatItem.SystemPrompt ?: return
                val tvContent = holder.getView<TextView>(R.id.tv_content)
                val ivExpandIcon = holder.getView<ImageView>(R.id.iv_expand_icon)
                val collapsedMaxLines = 5

                tvContent.text = prompt.content

                if (prompt.isExpanded) {
                    tvContent.maxLines = Int.MAX_VALUE
                    tvContent.ellipsize = null
                    ivExpandIcon.setImageResource(R.drawable.ic_expand_less)
                    ivExpandIcon.visibility = View.VISIBLE
                } else {
                    tvContent.maxLines = collapsedMaxLines
                    tvContent.ellipsize = android.text.TextUtils.TruncateAt.END
                    ivExpandIcon.setImageResource(R.drawable.ic_expand_more)
                    tvContent.post {
                        ivExpandIcon.visibility =
                            if (tvContent.lineCount > collapsedMaxLines) View.VISIBLE else View.GONE
                    }
                }

                holder.itemView.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        if (tvContent.lineCount > collapsedMaxLines) {
                            listener?.onSystemPromptToggle(pos)
                        }
                    }
                }
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?, payloads: List<Any>) {
                if (payloads.isNotEmpty()) {
                    // Toggle expand without full rebind
                    val tvContent = holder.getView<TextView>(R.id.tv_content)
                    val ivExpandIcon = holder.getView<ImageView>(R.id.iv_expand_icon)
                    val collapsedMaxLines = 5
                    val currentExpanded = tvContent.maxLines == Int.MAX_VALUE

                    if (currentExpanded) {
                        tvContent.maxLines = collapsedMaxLines
                        tvContent.ellipsize = android.text.TextUtils.TruncateAt.END
                        ivExpandIcon.setImageResource(R.drawable.ic_expand_more)
                        tvContent.post {
                            ivExpandIcon.visibility =
                                if (tvContent.lineCount > collapsedMaxLines) View.VISIBLE else View.GONE
                        }
                    } else {
                        tvContent.maxLines = Int.MAX_VALUE
                        tvContent.ellipsize = null
                        ivExpandIcon.setImageResource(R.drawable.ic_expand_less)
                        ivExpandIcon.visibility = View.VISIBLE
                    }
                } else {
                    super.onBind(holder, position, item, payloads)
                }
            }
        })

        addItemType(TYPE_USER, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_message_user, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val msg = item as? ChatItem.UserMessage ?: return
                holder.setText(R.id.tv_content, msg.content)

                val ivImage = holder.getView<ImageView>(R.id.iv_image)
                if (msg.imageUri != null) {
                    ivImage.setImageURI(android.net.Uri.parse(msg.imageUri))
                    ivImage.visibility = View.VISIBLE
                } else {
                    ivImage.setImageDrawable(null)
                    ivImage.visibility = View.GONE
                }

                if (msg.attachmentName != null) {
                    holder.setVisible(R.id.divider_attachment, true)
                    holder.setVisible(R.id.layout_attachment, true)
                    holder.setText(R.id.tv_attachment_name, msg.attachmentName)
                } else {
                    holder.setGone(R.id.divider_attachment, true)
                    holder.setGone(R.id.layout_attachment, true)
                }
            }
        })

        addItemType(TYPE_ASSISTANT, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_message_assistant, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val msg = item as? ChatItem.AssistantMessage ?: return
                val tvContent = holder.getView<TextView>(R.id.tv_content)
                markwon.setMarkdown(tvContent, msg.content)

                if (msg.modelName != null) {
                    holder.setText(R.id.tv_model_name, msg.modelName)
                    holder.setVisible(R.id.tv_model_name, true)
                } else {
                    holder.setGone(R.id.tv_model_name, true)
                }

                tvContent.setOnLongClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        listener?.onContentLongPress(msg.content)
                    }
                    true
                }
            }
        })

        addItemType(TYPE_STREAMING, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_streaming, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val streaming = item as? ChatItem.StreamingMessage ?: return
                val streamingContent = holder.itemView.getTag(R.id.tag_streaming_content) as? String ?: ""
                val isExpanded = holder.itemView.getTag(R.id.tag_streaming_expanded) as? Boolean ?: false

                val content = streamingContent.ifEmpty { streaming.content }
                if (streaming.isThinking && content.isEmpty()) {
                    holder.setVisible(R.id.progress_thinking, true)
                    holder.setText(R.id.tv_status, holder.itemView.context.getString(R.string.chat_thinking))
                } else {
                    holder.setGone(R.id.progress_thinking, true)
                    holder.setText(R.id.tv_status, holder.itemView.context.getString(R.string.chat_streaming_done))
                }

                applyExpandState(holder, content, isExpanded)

                holder.getView<View>(R.id.layout_header).setOnClickListener {
                    val newExpanded = !(holder.itemView.getTag(R.id.tag_streaming_expanded) as? Boolean ?: false)
                    holder.itemView.setTag(R.id.tag_streaming_expanded, newExpanded)
                    val currentContent = holder.itemView.getTag(R.id.tag_streaming_content) as? String ?: ""
                    applyExpandState(holder, currentContent, newExpanded)
                    if (newExpanded) {
                        holder.itemView.post {
                            val parent = holder.itemView.parent
                            if (parent is RecyclerView) {
                                val pos = holder.adapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    parent.smoothScrollToPosition(pos)
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    private fun applyExpandState(holder: QuickViewHolder, content: String, isExpanded: Boolean) {
        val ivExpand = holder.getView<ImageView>(R.id.iv_expand)
        val tvContent = holder.getView<TextView>(R.id.tv_content)
        if (isExpanded) {
            ivExpand.setImageResource(R.drawable.ic_expand_less)
            tvContent.visibility = View.VISIBLE
            tvContent.text = content
        } else {
            ivExpand.setImageResource(R.drawable.ic_expand_more)
            tvContent.visibility = View.GONE
        }
    }

    fun findStreamingViewHolder(recyclerView: RecyclerView): StreamingViewHolderProxy? {
        for (i in 0 until itemCount) {
            if (getItem(i) is ChatItem.StreamingMessage) {
                val holder = recyclerView.findViewHolderForAdapterPosition(i)
                if (holder is QuickViewHolder) {
                    return StreamingViewHolderProxy(holder)
                }
            }
        }
        return null
    }

    class StreamingViewHolderProxy(private val holder: QuickViewHolder) {
        val isExpanded: Boolean
            get() = holder.itemView.getTag(R.id.tag_streaming_expanded) as? Boolean ?: false

        val adapterPosition: Int
            get() = holder.adapterPosition

        fun updateStreamingContent(content: String) {
            holder.itemView.setTag(R.id.tag_streaming_content, content)
            holder.setGone(R.id.progress_thinking, true)
            holder.setText(R.id.tv_status, holder.itemView.context.getString(R.string.chat_streaming_responding))
            if (isExpanded) {
                holder.getView<TextView>(R.id.tv_content).text = content
            }
        }
    }
}
