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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.local.db.ChatDatabaseManager
import com.gao.chatbox.view.data.local.db.entity.ConversationWithLastMessage
import com.gao.chatbox.view.databinding.FragmentHistoryBinding
import com.gao.chatbox.view.ui.chat.ChatActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val dbManager by lazy { ChatDatabaseManager.getInstance(requireContext()) }
    private var adapter: ConversationAdapter? = null
    private var dataJob: Job? = null
    private var currentFilter: String? = null
    private var currentKeyword: String = ""

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
        loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dataJob?.cancel()
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
                currentKeyword = s?.toString()?.trim() ?: ""
                loadData()
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
            onItemLongClick = { item -> showDeleteConfirm(item) }
        )
        binding.rvConversations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
    }

    private fun loadData() {
        dataJob?.cancel()
        dataJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = when {
                currentKeyword.isNotEmpty() && currentFilter != null -> {
                    dbManager.searchConversationsWithLastMessage(currentKeyword)
                }
                currentFilter != null -> {
                    dbManager.getConversationsByTagWithLastMessage(currentFilter!!)
                }
                currentKeyword.isNotEmpty() -> {
                    dbManager.searchConversationsWithLastMessage(currentKeyword)
                }
                else -> {
                    dbManager.getAllConversationsWithLastMessage()
                }
            }

            flow.collectLatest { list ->
                val filtered = if (currentKeyword.isNotEmpty() && currentFilter != null) {
                    list.filter { it.conversation.displayTag == currentFilter }
                } else {
                    list
                }
                adapter?.submitList(filtered)
                binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showFilterDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            dbManager.getDistinctTags().collectLatest { tags ->
                if (!isAdded) return@collectLatest

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
                        currentFilter = if (which == 0) null else items[which]
                        updateFilterLabel()
                        loadData()
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()

                return@collectLatest
            }
        }
    }

    private fun updateFilterLabel() {
        if (currentFilter != null) {
            binding.tvActiveFilter.text = "筛选: $currentFilter"
            binding.tvActiveFilter.visibility = View.VISIBLE
            binding.tvActiveFilter.setOnClickListener {
                currentFilter = null
                updateFilterLabel()
                loadData()
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

    private fun showDeleteConfirm(item: ConversationWithLastMessage) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_conversation_title)
            .setMessage(R.string.delete_conversation_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    dbManager.deleteConversation(item.conversation.id)
                    Toast.makeText(requireContext(), R.string.msg_conversation_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
