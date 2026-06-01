package com.gao.chatbox.view.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SimpleExpandableListAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.util.ModelConfigManager
import com.google.android.material.appbar.MaterialToolbar
import com.tencent.mmkv.MMKV

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SYSTEM_PROMPT_CONTENT = "system_prompt_content"
        private const val EXTRA_SYSTEM_PROMPT_TAG = "system_prompt_tag"
        private const val KEY_WEB_SEARCH = "capability_web_search"

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
    private lateinit var btnWebSearch: ImageButton
    private lateinit var tvSelectedModel: TextView
    private lateinit var chatMessageAdapter: ChatMessageAdapter

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }
    private var systemPromptContent: String = ""
    private var systemPromptTag: String = ""
    private var webSearchEnabled: Boolean = false
    private var selectedModelName: String = ""

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onImagePicked(it) }
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onFilePicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        ModelConfigManager.init()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvSystemPrompt = findViewById<TextView>(R.id.tv_system_prompt)
        rvMessages = findViewById(R.id.rv_messages)
        etInput = findViewById(R.id.et_input)
        val btnNewChat = findViewById<ImageButton>(R.id.btn_new_chat)
        val btnSelectImage = findViewById<ImageButton>(R.id.btn_select_image)
        val btnSelectFile = findViewById<ImageButton>(R.id.btn_select_file)
        val btnSelectModel = findViewById<LinearLayout>(R.id.btn_select_model)
        tvSelectedModel = findViewById(R.id.tv_selected_model)
        btnWebSearch = findViewById(R.id.btn_web_search)
        val btnSend = findViewById<ImageButton>(R.id.btn_send)

        systemPromptTag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        systemPromptContent = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""

        // Init web search from global setting
        webSearchEnabled = mmkv.decodeBool(KEY_WEB_SEARCH, false)

        // Init default model
        val defaultConfig = ModelConfigManager.getDefault()
        selectedModelName = defaultConfig?.defaultModel?.ifEmpty { defaultConfig.models.firstOrNull() } ?: ""
        tvSelectedModel.text = selectedModelName.ifEmpty { getString(R.string.btn_select_model) }

        // Toolbar
        toolbar.title = systemPromptTag
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        if (systemPromptContent.isNotBlank()) {
            tvSystemPrompt.text = systemPromptContent
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
        btnSelectImage.setOnClickListener { imagePickerLauncher.launch("image/*") }
        btnSelectFile.setOnClickListener { filePickerLauncher.launch("*/*") }
        btnSelectModel.setOnClickListener { showModelSelectorDialog() }
        btnWebSearch.setOnClickListener { toggleWebSearch() }
        btnSend.setOnClickListener { onSend() }

        updateWebSearchIcon()
    }

    // region Send

    private fun onSend() {
        val text = etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val currentList = chatMessageAdapter.currentList.toMutableList()
        currentList.add(ChatMessage(role = "user", content = text))
        chatMessageAdapter.submitList(currentList)
        rvMessages.scrollToPosition(currentList.size - 1)

        etInput.text?.clear()
    }

    // endregion

    // region New Chat

    private fun onNewChat() {
        if (chatMessageAdapter.currentList.isEmpty()) {
            restartChat()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_chat_confirm_title)
            .setMessage(R.string.new_chat_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                restartChat()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun restartChat() {
        finish()
        start(this, systemPromptContent, systemPromptTag)
    }

    // endregion

    // region Image & File picker

    private fun onImagePicked(uri: Uri) {
        Toast.makeText(this, "图片已选择: $uri", Toast.LENGTH_SHORT).show()
        // TODO: attach image to message
    }

    private fun onFilePicked(uri: Uri) {
        Toast.makeText(this, "文件已选择: $uri", Toast.LENGTH_SHORT).show()
        // TODO: attach file to message
    }

    // endregion

    // region Web Search toggle

    private fun toggleWebSearch() {
        webSearchEnabled = !webSearchEnabled
        mmkv.encode(KEY_WEB_SEARCH, webSearchEnabled)
        updateWebSearchIcon()
    }

    private fun updateWebSearchIcon() {
        val color = if (webSearchEnabled) {
            ContextCompat.getColor(this, R.color.md_primary)
        } else {
            ContextCompat.getColor(this, R.color.md_outline)
        }
        btnWebSearch.setColorFilter(color)
    }

    // endregion

    // region Model Selector

    private fun showModelSelectorDialog() {
        val configs = ModelConfigManager.getAll()
        if (configs.isEmpty()) {
            Toast.makeText(this, R.string.no_model_available, Toast.LENGTH_SHORT).show()
            return
        }

        // Build group data: tag -> list of model names
        val groupList = mutableListOf<Map<String, String>>()
        val childList = mutableListOf<List<Map<String, String>>>()

        for (config in configs) {
            val models = config.models.toMutableList()
            if (models.isEmpty() && config.defaultModel.isNotEmpty()) {
                models.add(config.defaultModel)
            }
            if (models.isEmpty()) continue

            val groupTag = if (config.isDefault) "${config.tag} (默认)" else config.tag
            groupList.add(mapOf("groupTitle" to groupTag))

            val children = models.map { model ->
                val display = if (model == config.defaultModel) "$model ✓" else model
                mapOf(
                    "childTitle" to display,
                    "modelConfigId" to config.id,
                    "modelName" to model
                )
            }
            childList.add(children)
        }

        if (groupList.isEmpty()) {
            Toast.makeText(this, R.string.no_model_available, Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = SimpleExpandableListAdapter(
            this,
            groupList,
            android.R.layout.simple_expandable_list_item_1,
            arrayOf("groupTitle"),
            intArrayOf(android.R.id.text1),
            childList,
            android.R.layout.simple_list_item_1,
            arrayOf("childTitle"),
            intArrayOf(android.R.id.text1)
        )

        val listView = ExpandableListView(this).apply {
            setAdapter(adapter as ExpandableListAdapter)
            setPadding(48, 24, 48, 0)
            clipToPadding = false
        }

        // Expand all groups
        for (i in 0 until adapter.groupCount) {
            listView.expandGroup(i)
        }

        listView.setOnChildClickListener { _, _, groupPos, childPos, _ ->
            val child = childList[groupPos][childPos]
            val configId = child["modelConfigId"] ?: return@setOnChildClickListener true
            val modelName = child["modelName"] ?: return@setOnChildClickListener true

            selectedModelName = modelName

            // Set this config as default
            val config = configs.find { it.id == configId }
            if (config != null && !config.isDefault) {
                ModelConfigManager.update(config.copy(isDefault = true, defaultModel = modelName))
            } else if (config != null && config.defaultModel != modelName) {
                ModelConfigManager.update(config.copy(defaultModel = modelName))
            }

            tvSelectedModel.text = modelName
            Toast.makeText(this, "已选择: $modelName", Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
            true
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_model_title)
            .setView(listView)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private var dialog: AlertDialog? = null

    // endregion

    // region Delete

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_conversation_confirm_title)
            .setMessage(R.string.delete_conversation_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion
}
