package com.gao.chatbox.view.ui.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ChatItemBuilder {

    private const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

    fun buildTimestampIfNeeded(
        items: List<ChatItem>,
        newMessageTime: Long
    ): ChatItem.Timestamp? {
        val lastTimestamp =
            items.filterIsInstance<ChatItem.Timestamp>().lastOrNull() ?: return createTimestamp(
                newMessageTime
            )
        if (newMessageTime - lastTimestamp.timestampMillis > GAP_THRESHOLD_MS) {
            return createTimestamp(newMessageTime)
        }
        return null
    }

    fun buildInitialTimestamp(): ChatItem.Timestamp {
        return createTimestamp(System.currentTimeMillis())
    }

    private fun createTimestamp(millis: Long): ChatItem.Timestamp {
        return ChatItem.Timestamp(
            id = "ts_$millis",
            timeText = formatTimestamp(millis),
            timestampMillis = millis
        )
    }

    private fun formatTimestamp(millis: Long): String {
        val now = System.currentTimeMillis()
        val date = Date(millis)
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return when {
            millis >= todayStart -> "今天 ${timeFormat.format(date)}"
            millis >= todayStart - 86400000 -> "昨天 ${timeFormat.format(date)}"
            millis >= todayStart - 2 * 86400000 -> "前天 ${timeFormat.format(date)}"
            else -> dateFormat.format(date)
        }
    }
}
