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

class HistoryViewModel(
    private val context: Context,
    private val dbManager: ChatDatabaseManager
) : ViewModel() {

    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags

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

    private fun loadTags() {
        viewModelScope.launch {
            dbManager.getDistinctTags().collect { tagList ->
                _tags.value = tagList
            }
        }
    }

    fun setFilter(tag: String?) {
        _filter.value = tag
    }

    fun setKeyword(keyword: String) {
        _keyword.value = keyword
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            dbManager.deleteConversation(id)
            DebugLogManager.deleteLogFile(context, id)
        }
    }

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
