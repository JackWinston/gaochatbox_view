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
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.databinding.DialogSystemPromptBinding
import com.gao.chatbox.view.databinding.FragmentQuickStartBinding
import com.gao.chatbox.view.ui.chat.ChatActivity
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class QuickStartFragment : Fragment() {

    private var _binding: FragmentQuickStartBinding? = null
    private val binding get() = _binding!!
    private var adapter: SystemPromptAdapter? = null
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

        val layoutManager = FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            justifyContent = JustifyContent.CENTER
        }
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
            }
        )
        binding.rvPrompts.adapter = adapter
    }

    private fun observePrompts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.prompts.collect { prompts ->
                    adapter?.submitList(SystemPromptAdapter.buildItems(prompts))
                }
            }
        }
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
