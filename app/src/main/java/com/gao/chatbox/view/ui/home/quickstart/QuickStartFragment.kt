package com.gao.chatbox.view.ui.home.quickstart

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.databinding.DialogSystemPromptBinding
import com.gao.chatbox.view.databinding.FragmentQuickStartBinding
import com.gao.chatbox.view.ui.chat.ChatActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 快速开始页面 Fragment
 *
 * 功能概述：
 * - 以瀑布流网格（2列）展示所有系统提示词（角色预设）
 * - 支持点击提示词卡片直接进入聊天界面
 * - 支持新增、编辑、删除自定义提示词
 * - 支持拖拽排序自定义提示词（默认/预设提示词不可拖拽）
 * - 每张卡片显示提示词的标签(tag)和内容(content)预览
 *
 * 数据流：Fragment → ViewModel → SystemPromptManager → SharedPreferences
 * 用户交互：点击卡片 → ChatActivity.start() 启动新对话
 */
class QuickStartFragment : Fragment() {

    /** ViewBinding 引用，在 onDestroyView 时置空防止内存泄漏 */
    private var _binding: FragmentQuickStartBinding? = null
    private val binding get() = _binding!!

    /** 提示词列表适配器，负责渲染网格中的每张卡片 */
    private var adapter: SystemPromptAdapter? = null

    /** 当前展示的提示词列表快照，用于拖拽排序时的本地操作 */
    private val displayedPrompts = mutableListOf<SystemPrompt>()

    /** ViewModel，通过 Hilt Factory 注入 SystemPromptManager 依赖 */
    private val viewModel: QuickStartViewModel by viewModels {
        (requireActivity().application as ChatBoxApp).appComponent.quickStartViewModelFactory()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuickStartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 使用 2 列垂直瀑布流布局，卡片高度自适应内容
        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvPrompts.layoutManager = layoutManager

        setupAdapter()
        observePrompts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 初始化适配器并绑定各类回调事件
     *
     * 回调说明：
     * - onPromptClick: 点击提示词卡片 → 启动 ChatActivity 开始新对话
     * - onAddClick: 点击末尾的"+"卡片 → 弹出新增对话框
     * - onEditPrompt: 长按卡片选择编辑 → 弹出编辑对话框
     * - onDeletePrompt: 长按卡片选择删除 → 弹出确认对话框
     * - onStartDrag: 拖拽卡片的拖拽手柄 → 触发 ItemTouchHelper 开始拖拽
     */
    private fun setupAdapter() {
        adapter = SystemPromptAdapter(
            onPromptClick = { prompt ->
                ChatActivity.start(requireContext(), prompt.content, prompt.tag)
            },
            onAddClick = {
                showAddDialog()
            },
            onEditPrompt = { prompt ->
                showEditDialog(prompt)
            },
            onDeletePrompt = { prompt ->
                showDeleteConfirm(prompt)
            },
            onStartDrag = { position ->
                val viewHolder = binding.rvPrompts.findViewHolderForAdapterPosition(position)
                if (viewHolder != null) {
                    itemTouchHelper.startDrag(viewHolder)
                }
            }
        )
        binding.rvPrompts.adapter = adapter
        // 将 ItemTouchHelper 附着到 RecyclerView 以支持拖拽排序
        itemTouchHelper.attachToRecyclerView(binding.rvPrompts)
    }

    /**
     * 观察 ViewModel 中的提示词列表变化
     *
     * 使用 repeatOnLifecycle(STARTED) 确保只在页面可见时收集数据，
     * 避免后台无效更新。收到新数据后同步更新 displayedPrompts 快照，
     * 并通过 buildItems() 将提示词列表转换为适配器所需的 Item 列表
     * （包含提示词卡片 + 末尾的"添加"卡片）。
     */
    private fun observePrompts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.prompts.collect { prompts ->
                    displayedPrompts.clear()
                    displayedPrompts.addAll(prompts)
                    adapter?.submitList(SystemPromptAdapter.buildItems(displayedPrompts))
                }
            }
        }
    }

    /**
     * 拖拽排序辅助器
     *
     * 支持上下左右四个方向拖拽（瀑布流布局需要同时支持水平方向）。
     *
     * 关键逻辑：
     * - getMovementFlags: 默认提示词（isDefault=true）不可拖拽，返回空 movementFlags
     * - onMove: 拖拽过程中实时更新 displayedPrompts 列表顺序并刷新 UI
     * - clearView: 拖拽结束后将新顺序持久化到 ViewModel（写入 SharedPreferences）
     * - isLongPressDragEnabled: 禁用长按拖拽，只允许通过拖拽手柄触发
     */
    private val itemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0  // 不支持滑动删除
        ) {
            /** 标记本次拖拽是否发生了位置移动，用于 clearView 时判断是否需要持久化 */
            private var hasMoved = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION || position >= displayedPrompts.size) {
                    return makeMovementFlags(0, 0)
                }
                val prompt = displayedPrompts[position]
                return if (prompt.isDefault) {
                    // 默认提示词不可拖拽
                    makeMovementFlags(0, 0)
                } else {
                    makeMovementFlags(
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                            ItemTouchHelper.START or ItemTouchHelper.END,
                        0
                    )
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                // 边界检查：防止无效位置
                if (
                    fromPosition == RecyclerView.NO_POSITION ||
                    toPosition == RecyclerView.NO_POSITION ||
                    fromPosition >= displayedPrompts.size ||
                    toPosition >= displayedPrompts.size
                ) {
                    return false
                }
                val fromPrompt = displayedPrompts[fromPosition]
                val toPrompt = displayedPrompts[toPosition]
                // 如果拖拽的起始或目标是默认提示词，则不允许移动
                if (fromPrompt.isDefault || toPrompt.isDefault) {
                    return false
                }

                // 在本地列表中执行位置交换并刷新适配器
                val movedPrompt = displayedPrompts.removeAt(fromPosition)
                displayedPrompts.add(toPosition, movedPrompt)
                adapter?.submitList(SystemPromptAdapter.buildItems(displayedPrompts))
                hasMoved = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 拖拽结束后，如果有位置变动则持久化新顺序
                if (hasMoved) {
                    hasMoved = false
                    viewModel.reorderPrompts(displayedPrompts.toList())
                }
            }

            // 禁用长按拖拽，只允许通过拖拽手柄（iv_drag_handle）触发
            override fun isLongPressDragEnabled(): Boolean = false
        })
    }

    /**
     * 显示新增提示词对话框
     *
     * 对话框包含两个输入字段：
     * - etTag: 提示词标签（显示在卡片标题位置）
     * - etContent: 提示词内容（系统提示词全文，传给 AI 模型）
     *
     * 确认时校验两个字段均非空，然后通过 ViewModel 保存到本地存储。
     */
    private fun showAddDialog() {
        val dialogBinding = DialogSystemPromptBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_add)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val tag = dialogBinding.etTag.text?.toString()?.trim() ?: ""
                val content = dialogBinding.etContent.text?.toString()?.trim() ?: ""
                if (tag.isNotEmpty() && content.isNotEmpty()) {
                    viewModel.addPrompt(SystemPrompt(content = content, tag = tag))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 显示编辑提示词对话框
     *
     * 预填充当前提示词的 tag 和 content，用户修改后通过 ViewModel 更新。
     * 使用 prompt.copy() 创建副本以保持 id/isDefault/isPreset 等字段不变。
     */
    private fun showEditDialog(prompt: SystemPrompt) {
        val dialogBinding = DialogSystemPromptBinding.inflate(layoutInflater)

        dialogBinding.etTag.setText(prompt.tag)
        dialogBinding.etContent.setText(prompt.content)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val tag = dialogBinding.etTag.text?.toString()?.trim() ?: ""
                val content = dialogBinding.etContent.text?.toString()?.trim() ?: ""
                if (tag.isNotEmpty() && content.isNotEmpty()) {
                    viewModel.updatePrompt(prompt.copy(tag = tag, content = content))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 显示删除确认对话框
     *
     * 二次确认防止误删。确认后通过 ViewModel 按 id 删除提示词，
     * 数据变更会自动触发 observePrompts() 刷新列表。
     */
    private fun showDeleteConfirm(prompt: SystemPrompt) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewModel.deletePrompt(prompt.id)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
