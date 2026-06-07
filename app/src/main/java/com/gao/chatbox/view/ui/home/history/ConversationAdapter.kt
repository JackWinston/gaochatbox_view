package com.gao.chatbox.view.ui.home.history

import android.content.Context
import android.view.ViewGroup
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onItemClick: (ConversationWithLastMessage) -> Unit,
    private val onItemLongClick: (ConversationWithLastMessage) -> Unit
) : BaseQuickAdapter<ConversationWithLastMessage, QuickViewHolder>() {

    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
    }

    init {
        setOnItemClickListener { _, _, position ->
            getItem(position)?.let { onItemClick(it) }
        }
        setOnItemLongClickListener { _, _, position ->
            getItem(position)?.let { onItemLongClick(it) }
            true
        }
    }

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
        return QuickViewHolder(R.layout.item_conversation, parent)
    }

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: ConversationWithLastMessage?) {
        item ?: return
        holder.setText(R.id.tv_display_tag, item.conversation.displayTag ?: item.conversation.title)
        holder.setText(R.id.tv_last_message, item.lastMessage ?: "")
        holder.setText(R.id.tv_timestamp, item.conversation.updatedAt.formatRelative())
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
