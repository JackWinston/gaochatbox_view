package com.gao.chatbox.view.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.google.android.material.appbar.MaterialToolbar

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SYSTEM_PROMPT_CONTENT = "system_prompt_content"
        private const val EXTRA_SYSTEM_PROMPT_TAG = "system_prompt_tag"

        fun start(context: Context, content: String, tag: String) {
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_SYSTEM_PROMPT_CONTENT, content)
                putExtra(EXTRA_SYSTEM_PROMPT_TAG, tag)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var chatMessageAdapter: ChatMessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvSystemPrompt = findViewById<TextView>(R.id.tv_system_prompt)
        rvMessages = findViewById(R.id.rv_messages)
        etInput = findViewById(R.id.et_input)
        val btnNewChat = findViewById<ImageButton>(R.id.btn_new_chat)
        val btnSelectImage = findViewById<ImageButton>(R.id.btn_select_image)
        val btnSelectFile = findViewById<ImageButton>(R.id.btn_select_file)
        val btnSelectModel = findViewById<ImageButton>(R.id.btn_select_model)
        val btnSend = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_send)

        val tag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        val content = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""

        // Toolbar
        toolbar.title = tag
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_delete -> {
                    showDeleteConfirmDialog()
                    true
                }
                else -> false
            }
        }

        // System prompt
        if (content.isNotBlank()) {
            tvSystemPrompt.text = content
            tvSystemPrompt.visibility = android.view.View.VISIBLE
        }

        // RecyclerView
        chatMessageAdapter = ChatMessageAdapter()
        rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatMessageAdapter
        }

        // Bottom toolbar actions
        btnNewChat.setOnClickListener { onNewChat() }
        btnSelectImage.setOnClickListener { onSelectImage() }
        btnSelectFile.setOnClickListener { onSelectFile() }
        btnSelectModel.setOnClickListener { onSelectModel() }
        btnSend.setOnClickListener { onSend() }
    }

    private fun onSend() {
        val text = etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val currentList = chatMessageAdapter.currentList.toMutableList()
        currentList.add(ChatMessage(role = "user", content = text))
        chatMessageAdapter.submitList(currentList)
        rvMessages.scrollToPosition(currentList.size - 1)

        etInput.text?.clear()
    }

    private fun onNewChat() {
        chatMessageAdapter.submitList(emptyList())
    }

    private fun onSelectImage() {
        // TODO: Implement image picker
    }

    private fun onSelectFile() {
        // TODO: Implement file picker
    }

    private fun onSelectModel() {
        // TODO: Implement model selector
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_conversation_confirm_title)
            .setMessage(R.string.delete_conversation_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
