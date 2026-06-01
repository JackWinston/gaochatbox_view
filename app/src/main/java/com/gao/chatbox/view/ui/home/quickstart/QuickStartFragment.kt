package com.gao.chatbox.view.ui.home.quickstart

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt
import com.gao.chatbox.view.ui.chat.ChatActivity
import com.gao.chatbox.view.util.SystemPromptManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class QuickStartFragment : Fragment() {

    private lateinit var rvPrompts: RecyclerView
    private var adapter: SystemPromptAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_quick_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        SystemPromptManager.init()
        rvPrompts = view.findViewById(R.id.rv_prompts)

        val layoutManager = FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            justifyContent = JustifyContent.CENTER
        }
        rvPrompts.layoutManager = layoutManager

        refreshList()
    }

    private fun refreshList() {
        val prompts = SystemPromptManager.getAll()
        adapter = SystemPromptAdapter(
            prompts = prompts,
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
        rvPrompts.adapter = adapter
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_system_prompt, null)
        val etTag = dialogView.findViewById<TextInputEditText>(R.id.et_tag)
        val etContent = dialogView.findViewById<TextInputEditText>(R.id.et_content)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_add)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val tag = etTag.text?.toString()?.trim() ?: ""
                val content = etContent.text?.toString()?.trim() ?: ""
                if (tag.isNotEmpty() && content.isNotEmpty()) {
                    SystemPromptManager.add(
                        SystemPrompt(content = content, tag = tag)
                    )
                    refreshList()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showEditDialog(prompt: SystemPrompt) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_system_prompt, null)
        val etTag = dialogView.findViewById<TextInputEditText>(R.id.et_tag)
        val etContent = dialogView.findViewById<TextInputEditText>(R.id.et_content)

        etTag.setText(prompt.tag)
        etContent.setText(prompt.content)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_edit)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val tag = etTag.text?.toString()?.trim() ?: ""
                val content = etContent.text?.toString()?.trim() ?: ""
                if (tag.isNotEmpty() && content.isNotEmpty()) {
                    SystemPromptManager.update(
                        prompt.copy(tag = tag, content = content)
                    )
                    refreshList()
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
                SystemPromptManager.delete(prompt.id)
                refreshList()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
