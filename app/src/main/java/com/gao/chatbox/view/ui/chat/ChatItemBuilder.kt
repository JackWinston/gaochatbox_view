package com.gao.chatbox.view.ui.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 聊天 Item 构建器
 *
 * 负责创建时间戳类型的 ChatItem。
 * 当消息间隔超过 5 分钟时自动插入时间分隔线，提升聊天记录的可读性。
 *
 * 时间格式化规则：
 * - 今天 → "今天 HH:mm"
 * - 昨天 → "昨天 HH:mm"
 * - 前天 → "前天 HH:mm"
 * - 更早 → "M月d日 HH:mm"
 */
object ChatItemBuilder {

    /** 消息间隔阈值：5 分钟（超过此间隔插入时间戳） */
    private const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

    /** 时间格式：仅时分 */
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    /** 日期格式：月日时分 */
    private val dateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

    /**
     * 判断是否需要插入时间戳
     *
     * @param items 当前已有的 ChatItem 列表
     * @param newMessageTime 新消息的时间戳
     * @return 需要插入时返回 Timestamp Item，否则返回 null
     */
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

    /** 构建对话开始时的初始时间戳 */
    fun buildInitialTimestamp(): ChatItem.Timestamp {
        return createTimestamp(System.currentTimeMillis())
    }

    /** 创建时间戳 Item */
    private fun createTimestamp(millis: Long): ChatItem.Timestamp {
        return ChatItem.Timestamp(
            id = "ts_$millis",
            timeText = formatTimestamp(millis),
            timestampMillis = millis
        )
    }

    /**
     * 格式化时间戳为友好的文本
     *
     * 使用相对日期（今天/昨天/前天）提升可读性，
     * 超过前天的使用绝对日期格式。
     */
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
