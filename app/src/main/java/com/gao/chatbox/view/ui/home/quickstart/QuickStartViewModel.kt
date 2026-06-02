package com.gao.chatbox.view.ui.home.quickstart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.util.SystemPromptManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class QuickStartViewModel(
    private val systemPromptManager: SystemPromptManager
) : ViewModel() {

    private val _prompts = MutableStateFlow<List<SystemPrompt>>(emptyList())
    val prompts: StateFlow<List<SystemPrompt>> = _prompts

    init {
        systemPromptManager.init()
        refreshPrompts()
    }

    fun refreshPrompts() {
        _prompts.value = systemPromptManager.getAll()
    }

    fun addPrompt(prompt: SystemPrompt) {
        systemPromptManager.add(prompt)
        refreshPrompts()
    }

    fun updatePrompt(prompt: SystemPrompt) {
        systemPromptManager.update(prompt)
        refreshPrompts()
    }

    fun deletePrompt(id: String) {
        systemPromptManager.delete(id)
        refreshPrompts()
    }

    class Factory @Inject constructor(
        private val systemPromptManager: SystemPromptManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickStartViewModel(systemPromptManager) as T
        }
    }
}
