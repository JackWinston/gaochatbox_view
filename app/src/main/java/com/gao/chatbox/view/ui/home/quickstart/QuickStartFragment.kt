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

class QuickStartFragment : Fragment() {

    private var _binding: FragmentQuickStartBinding? = null
    private val binding get() = _binding!!
    private var adapter: SystemPromptAdapter? = null
    private val displayedPrompts = mutableListOf<SystemPrompt>()
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

        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvPrompts.layoutManager = layoutManager

        setupAdapter()
        observePrompts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

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
        itemTouchHelper.attachToRecyclerView(binding.rvPrompts)
    }

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

    private val itemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
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
                if (fromPrompt.isDefault || toPrompt.isDefault) {
                    return false
                }

                val movedPrompt = displayedPrompts.removeAt(fromPosition)
                displayedPrompts.add(toPosition, movedPrompt)
                adapter?.submitList(SystemPromptAdapter.buildItems(displayedPrompts))
                hasMoved = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (hasMoved) {
                    hasMoved = false
                    viewModel.reorderPrompts(displayedPrompts.toList())
                }
            }

            override fun isLongPressDragEnabled(): Boolean = false
        })
    }

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
