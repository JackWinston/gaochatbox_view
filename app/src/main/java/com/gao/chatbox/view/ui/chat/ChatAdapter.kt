package com.gao.chatbox.view.ui.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        const val TYPE_TOOL_CALL = 5
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var showCharCount = false
    private var showTokenCount = false
    private var showModelName = false
    private var showTimestamp = false

    fun updateSettings(
        showCharCount: Boolean,
        showTokenCount: Boolean,
        showModelName: Boolean,
        showTimestamp: Boolean
    ) {
        this.showCharCount = showCharCount
        this.showTokenCount = showTokenCount
        this.showModelName = showModelName
        this.showTimestamp = showTimestamp
    }

    interface ChatAdapterListener {
        fun onSystemPromptToggle(position: Int)
        fun onContentLongPress(content: String)
        fun onStreamingStop()
    }

    init {
        onItemViewType { position, list ->
            when (list[position]) {
                is ChatItem.Timestamp -> TYPE_TIMESTAMP
                is ChatItem.SystemPrompt -> TYPE_SYSTEM_PROMPT
                is ChatItem.UserMessage -> TYPE_USER
                is ChatItem.AssistantMessage -> TYPE_ASSISTANT
                is ChatItem.StreamingMessage -> TYPE_STREAMING
                is ChatItem.ToolCallMessage -> TYPE_TOOL_CALL
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

                // 构建提示信息
                val metaParts = mutableListOf<String>()
                if (showCharCount) {
                    metaParts.add("${msg.content.length}字")
                }
                if (showTokenCount && msg.tokenCount > 0) {
                    metaParts.add("${msg.tokenCount}tok")
                }
                if (showModelName && !msg.modelName.isNullOrEmpty()) {
                    metaParts.add(msg.modelName!!)
                }
                if (showTimestamp && msg.createdAt > 0) {
                    metaParts.add(timeFormat.format(Date(msg.createdAt)))
                }

                val tvMetaInfo = holder.getView<TextView>(R.id.tv_meta_info)
                if (metaParts.isNotEmpty()) {
                    tvMetaInfo.text = metaParts.joinToString(" · ")
                    tvMetaInfo.visibility = View.VISIBLE
                } else {
                    tvMetaInfo.visibility = View.GONE
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
                val progressThinking = holder.getView<ProgressBar>(R.id.progress_thinking)
                val tvStatus = holder.getView<TextView>(R.id.tv_status)
                if (streaming.isThinking && content.isEmpty()) {
                    progressThinking.visibility = View.VISIBLE
                    tvStatus.text = holder.itemView.context.getString(R.string.chat_thinking)
                } else {
                    progressThinking.visibility = View.GONE
                    tvStatus.text = holder.itemView.context.getString(R.string.chat_streaming_done)
                }

                applyExpandState(holder, content, isExpanded)

                // 更新统计信息
                val tvStats = holder.getView<TextView>(R.id.tv_stats)
                if (streaming.thinkingStartTime > 0) {
                    val elapsedSeconds = ((System.currentTimeMillis() - streaming.thinkingStartTime) / 1000).toInt()
                    tvStats.text = holder.itemView.context.getString(
                        R.string.chat_streaming_stats,
                        elapsedSeconds,
                        streaming.charCount
                    )
                    tvStats.visibility = View.VISIBLE
                } else {
                    tvStats.visibility = View.GONE
                }

                // 中止按钮 - 仅在流式传输过程中显示
                val btnStop = holder.getView<View>(R.id.btn_stop)
                if (streaming.isThinking) {
                    btnStop.visibility = View.VISIBLE
                    btnStop.setOnClickListener {
                        listener?.onStreamingStop()
                    }
                } else {
                    btnStop.visibility = View.GONE
                }

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

        addItemType(TYPE_TOOL_CALL, object : OnMultiItem<ChatItem, QuickViewHolder>() {
            override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
                return QuickViewHolder(R.layout.item_chat_tool_call, parent)
            }

            override fun onBind(holder: QuickViewHolder, position: Int, item: ChatItem?) {
                val toolCall = item as? ChatItem.ToolCallMessage ?: return

                holder.setText(R.id.tv_tool_name, toolCall.toolName)
                holder.setText(R.id.tv_arguments, toolCall.arguments)

                val progressBar = holder.getView<ProgressBar>(R.id.progress_tool)
                val tvStatus = holder.getView<TextView>(R.id.tv_status)
                val tvResult = holder.getView<TextView>(R.id.tv_result)

                when (toolCall.status) {
                    ChatItem.ToolCallStatus.PENDING -> {
                        progressBar.visibility = View.VISIBLE
                        tvStatus.text = "等待中"
                        tvResult.visibility = View.GONE
                    }
                    ChatItem.ToolCallStatus.EXECUTING -> {
                        progressBar.visibility = View.VISIBLE
                        tvStatus.text = "执行中..."
                        tvResult.visibility = View.GONE
                    }
                    ChatItem.ToolCallStatus.COMPLETED -> {
                        progressBar.visibility = View.GONE
                        tvStatus.text = "完成"
                        tvResult.text = toolCall.result
                        tvResult.visibility = View.VISIBLE
                    }
                    ChatItem.ToolCallStatus.ERROR -> {
                        progressBar.visibility = View.GONE
                        tvStatus.text = "失败"
                        tvResult.text = toolCall.result
                        tvResult.visibility = View.VISIBLE
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

        fun updateStreamingContent(content: String, thinkingStartTime: Long = 0L, charCount: Int = 0) {
            holder.itemView.setTag(R.id.tag_streaming_content, content)
            holder.setGone(R.id.progress_thinking, true)
            holder.setText(R.id.tv_status, holder.itemView.context.getString(R.string.chat_streaming_responding))
            if (isExpanded) {
                holder.getView<TextView>(R.id.tv_content).text = content
            }

            // 更新统计信息
            val tvStats = holder.getView<TextView>(R.id.tv_stats)
            if (thinkingStartTime > 0) {
                val elapsedSeconds = ((System.currentTimeMillis() - thinkingStartTime) / 1000).toInt()
                tvStats.text = holder.itemView.context.getString(
                    R.string.chat_streaming_stats,
                    elapsedSeconds,
                    charCount
                )
                tvStats.visibility = View.VISIBLE
            } else {
                tvStats.visibility = View.GONE
            }
        }
    }
}
