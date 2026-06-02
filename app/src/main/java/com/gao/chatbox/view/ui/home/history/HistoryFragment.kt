package com.gao.chatbox.view.ui.home.history

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import com.gao.chatbox.view.databinding.FragmentHistoryBinding
import com.gao.chatbox.view.ui.chat.ChatActivity
import com.gao.chatbox.view.ui.debug.DebugLogActivity
import kotlinx.coroutines.launch

/**
 * 历史记录页面 Fragment
 *
 * 功能概述：
 * - 展示所有历史对话列表，每条显示标题、最后一条消息预览、相对时间戳
 * - 支持关键词搜索对话（按标题/消息内容模糊匹配）
 * - 支持按标签(tag)筛选对话
 * - 点击对话项进入聊天详情页（ChatActivity）
 * - 长按对话项弹出菜单：查看调试日志 / 删除对话
 *
 * 数据流：Fragment → ViewModel → ChatDatabaseManager → Room Database
 */
class HistoryFragment : Fragment() {

    /** ViewBinding 引用，onDestroyView 时置空防止内存泄漏 */
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    /** 对话列表适配器 */
    private var adapter: ConversationAdapter? = null

    /** ViewModel，通过 Hilt Factory 注入 ChatDatabaseManager 依赖 */
    private val viewModel: HistoryViewModel by viewModels {
        (requireActivity().application as ChatBoxApp).appComponent.historyViewModelFactory()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupSearch()
        setupRecyclerView()
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 设置 Toolbar 菜单项点击事件
     * 筛选按钮（action_filter）点击后弹出标签筛选对话框
     */
    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_filter -> {
                    showFilterDialog()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 设置搜索框
     * - 实时监听文本变化，每次输入都触发 ViewModel 更新关键词并重新查询
     * - 点击搜索键盘按钮时收起软键盘
     */
    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setKeyword(s?.toString()?.trim() ?: "")
            }
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                true
            } else false
        }
    }

    /**
     * 设置 RecyclerView
     * - 点击对话项 → 打开已有对话的聊天页面
     * - 长按对话项 → 弹出操作菜单（调试日志 / 删除）
     */
    private fun setupRecyclerView() {
        adapter = ConversationAdapter(
            onItemClick = { item -> openConversation(item) },
            onItemLongClick = { item -> showLongPressMenu(item) }
        )
        binding.rvConversations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
    }

    /**
     * 观察 ViewModel 中的对话列表数据变化
     *
     * 数据流程：
     * 1. ViewModel 通过 Room Flow 获取所有对话（或按关键词搜索）
     * 2. Fragment 收到数据后，再根据当前标签筛选条件进行二次过滤
     * 3. 将过滤后的列表提交给适配器渲染
     * 4. 如果列表为空，显示空状态提示文本
     */
    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.conversations.collect { list ->
                        val filter = viewModel.filter.value
                        val filtered = if (filter != null) {
                            list.filter { it.conversation.displayTag == filter }
                        } else {
                            list
                        }
                        adapter?.submitList(filtered)
                        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    /**
     * 显示标签筛选对话框
     *
     * 从 ViewModel 获取所有已使用的标签列表，加上"全部"选项，
     * 以单选列表形式展示。选择后更新筛选条件并刷新列表。
     */
    private fun showFilterDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tags.collect { tags ->
                if (!isAdded) return@collect

                val currentFilter = viewModel.filter.value
                val items = mutableListOf(getString(R.string.filter_all))
                items.addAll(tags)

                val checkedIndex = if (currentFilter == null) 0
                else {
                    val idx = items.indexOf(currentFilter)
                    if (idx >= 0) idx else 0
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.filter_by_tag)
                    .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                        val newFilter = if (which == 0) null else items[which]
                        viewModel.setFilter(newFilter)
                        updateFilterLabel(newFilter)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()

                return@collect
            }
        }
    }

    /**
     * 更新筛选标签的显示状态
     *
     * @param filter 当前筛选标签，null 表示未筛选（隐藏标签）
     * 点击已激活的筛选标签可清除筛选
     */
    private fun updateFilterLabel(filter: String?) {
        if (filter != null) {
            binding.tvActiveFilter.text = "筛选: $filter"
            binding.tvActiveFilter.visibility = View.VISIBLE
            binding.tvActiveFilter.setOnClickListener {
                viewModel.setFilter(null)
                updateFilterLabel(null)
            }
        } else {
            binding.tvActiveFilter.visibility = View.GONE
        }
    }

    /**
     * 打开已有对话的聊天页面
     * 传入对话 ID、系统提示词内容和标签，ChatActivity 会加载历史消息
     */
    private fun openConversation(item: ConversationWithLastMessage) {
        val conv = item.conversation
        ChatActivity.startExisting(
            requireContext(),
            conversationId = conv.id,
            systemPromptContent = conv.systemPrompt ?: "",
            systemPromptTag = conv.systemPromptTag ?: "",
            displayTag = conv.displayTag ?: conv.title
        )
    }

    /**
     * 显示长按操作菜单
     * 提供两个选项：查看调试日志、删除对话
     */
    private fun showLongPressMenu(item: ConversationWithLastMessage) {
        val items = arrayOf(
            getString(R.string.menu_debug),
            getString(R.string.delete_conversation_title)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.conversation.displayTag ?: item.conversation.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openDebugLog(item)
                    1 -> showDeleteConfirm(item)
                }
            }
            .show()
    }

    /** 打开调试日志页面，查看该对话的 API 请求/响应日志 */
    private fun openDebugLog(item: ConversationWithLastMessage) {
        DebugLogActivity.start(requireContext(), item.conversation.id)
    }

    /** 显示删除确认对话框，确认后删除对话及其关联的日志文件 */
    private fun showDeleteConfirm(item: ConversationWithLastMessage) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_conversation_title)
            .setMessage(R.string.delete_conversation_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewModel.deleteConversation(item.conversation.id)
                Toast.makeText(requireContext(), R.string.msg_conversation_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
