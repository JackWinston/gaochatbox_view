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

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private var adapter: ConversationAdapter? = null
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

    private fun openDebugLog(item: ConversationWithLastMessage) {
        DebugLogActivity.start(requireContext(), item.conversation.id)
    }

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
