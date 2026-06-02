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

/**
 * 快速开始页面的 ViewModel
 *
 * 职责：
 * - 管理系统提示词（角色预设）的 CRUD 操作
 * - 通过 StateFlow 向 Fragment 暴露提示词列表数据
 * - 所有写操作（增删改排序）完成后自动刷新列表
 *
 * 数据流：ViewModel → SystemPromptManager → SharedPreferences
 * SystemPromptManager 封装了本地持久化逻辑，ViewModel 负责协程调度和状态管理。
 */
class QuickStartViewModel(
    private val systemPromptManager: SystemPromptManager
) : ViewModel() {

    /** 可变的提示词列表，仅 ViewModel 内部可写 */
    private val _prompts = MutableStateFlow<List<SystemPrompt>>(emptyList())

    /** 对外暴露的只读提示词列表，Fragment 通过 collect 观察变化 */
    val prompts: StateFlow<List<SystemPrompt>> = _prompts

    init {
        viewModelScope.launch {
            // 初始化 SystemPromptManager（加载预设提示词等）
            systemPromptManager.init()
            refreshPrompts()
        }
    }

    /** 从 SystemPromptManager 重新加载全部提示词并更新 StateFlow */
    fun refreshPrompts() {
        viewModelScope.launch {
            _prompts.value = systemPromptManager.getAll()
        }
    }

    /** 新增提示词并刷新列表 */
    fun addPrompt(prompt: SystemPrompt) {
        viewModelScope.launch {
            systemPromptManager.add(prompt)
            refreshPrompts()
        }
    }

    /** 更新提示词（按 id 匹配）并刷新列表 */
    fun updatePrompt(prompt: SystemPrompt) {
        viewModelScope.launch {
            systemPromptManager.update(prompt)
            refreshPrompts()
        }
    }

    /** 按 id 删除提示词并刷新列表 */
    fun deletePrompt(id: String) {
        viewModelScope.launch {
            systemPromptManager.delete(id)
            refreshPrompts()
        }
    }

    /**
     * 持久化拖拽排序后的新顺序
     * @param prompts 排序后的完整提示词列表
     */
    fun reorderPrompts(prompts: List<SystemPrompt>) {
        viewModelScope.launch {
            systemPromptManager.reorderPrompts(prompts)
            refreshPrompts()
        }
    }

    /**
     * ViewModel 工厂类，通过 Hilt @Inject 注入 SystemPromptManager 依赖
     *
     * 使用 ViewModelProvider.Factory 而非 Hilt 的 @HiltViewModel，
     * 因为 ViewModel 构造函数需要非默认依赖。
     * 在 Fragment 中通过 viewModels { factory } 委托使用。
     */
    class Factory @Inject constructor(
        private val systemPromptManager: SystemPromptManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickStartViewModel(systemPromptManager) as T
        }
    }
}
