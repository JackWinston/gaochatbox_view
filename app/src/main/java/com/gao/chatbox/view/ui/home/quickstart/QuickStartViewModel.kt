package com.gao.chatbox.view.ui.home.quickstart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.util.SystemPromptManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class QuickStartViewModel(
    private val systemPromptManager: SystemPromptManager
) : ViewModel() {

    private val _prompts = MutableStateFlow<List<SystemPrompt>>(emptyList())
    val prompts: StateFlow<List<SystemPrompt>> = _prompts

    init {
        viewModelScope.launch {
            systemPromptManager.init()
            refreshPrompts()
        }
    }

    fun refreshPrompts() {
        viewModelScope.launch {
            _prompts.value = systemPromptManager.getAll()
        }
    }

    fun addPrompt(prompt: SystemPrompt) {
        viewModelScope.launch {
            systemPromptManager.add(prompt)
            refreshPrompts()
        }
    }

    fun updatePrompt(prompt: SystemPrompt) {
        viewModelScope.launch {
            systemPromptManager.update(prompt)
            refreshPrompts()
        }
    }

    fun deletePrompt(id: String) {
        viewModelScope.launch {
            systemPromptManager.delete(id)
            refreshPrompts()
        }
    }

    fun reorderPrompts(prompts: List<SystemPrompt>) {
        viewModelScope.launch {
            systemPromptManager.reorderPrompts(prompts)
            refreshPrompts()
        }
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
