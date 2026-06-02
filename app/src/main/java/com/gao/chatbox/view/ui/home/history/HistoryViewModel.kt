package com.gao.chatbox.view.ui.home.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import com.gao.chatbox.view.util.DebugLogManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 历史记录页面的 ViewModel
 *
 * 职责：
 * - 管理对话列表的查询（支持关键词搜索）
 * - 管理标签筛选状态
 * - 管理标签列表的加载
 * - 处理对话删除操作
 *
 * 数据流：
 * - 对话列表：keyword 变化 → flatMapLatest → Room Flow → StateFlow
 * - 标签列表：Room Flow → StateFlow
 * - 筛选/关键词：Fragment 通过 setter 更新，Fragment 端二次过滤
 */
class HistoryViewModel(
    private val context: Context,
    private val dbManager: ChatDatabaseManager
) : ViewModel() {

    /** 当前筛选标签，null 表示不筛选 */
    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter

    /** 当前搜索关键词，空字符串表示不搜索 */
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    /** 所有已使用的标签列表，用于筛选对话框 */
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags

    /**
     * 对话列表数据源
     *
     * 使用 flatMapLatest 实现响应式查询：
     * - 当 keyword 非空时，调用搜索方法（按标题/消息内容模糊匹配）
     * - 当 keyword 为空时，获取全部对话
     * - keyword 变化时自动取消旧查询、启动新查询
     *
     * stateIn 将 Flow 转为 StateFlow，Lazily 策略表示有订阅者时才开始收集
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationWithLastMessage>> =
        _keyword.flatMapLatest { kw ->
            when {
                kw.isNotEmpty() -> dbManager.searchConversationsWithLastMessage(kw)
                else -> dbManager.getAllConversationsWithLastMessage()
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        loadTags()
    }

    /** 从数据库加载所有已使用的标签，持续观察变化 */
    private fun loadTags() {
        viewModelScope.launch {
            dbManager.getDistinctTags().collect { tagList ->
                _tags.value = tagList
            }
        }
    }

    /** 设置筛选标签 */
    fun setFilter(tag: String?) {
        _filter.value = tag
    }

    /** 设置搜索关键词，触发 conversations Flow 重新查询 */
    fun setKeyword(keyword: String) {
        _keyword.value = keyword
    }

    /**
     * 删除对话
     * 同时删除数据库记录和关联的调试日志文件
     */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            dbManager.deleteConversation(id)
            DebugLogManager.deleteLogFile(context, id)
        }
    }

    /**
     * ViewModel 工厂类
     * 通过 Hilt @Inject 注入 Context 和 ChatDatabaseManager 依赖
     */
    class Factory @Inject constructor(
        private val context: Context,
        private val dbManager: ChatDatabaseManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(context, dbManager) as T
        }
    }
}
