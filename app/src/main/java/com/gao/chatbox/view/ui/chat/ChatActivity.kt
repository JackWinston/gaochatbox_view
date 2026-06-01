package com.gao.chatbox.view.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.remote.StreamEvent
import com.gao.chatbox.view.data.repository.ChatRepository
import com.gao.chatbox.view.data.repository.MessageContext
import com.gao.chatbox.view.util.ModelConfigManager
import com.google.android.material.appbar.MaterialToolbar
import com.tencent.mmkv.MMKV
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity(), ChatAdapter.ChatAdapterListener {

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
    private lateinit var chatAdapter: ChatAdapter

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }
    private val chatRepository: ChatRepository by lazy { ChatRepository.getInstance(this) }
    private var systemPromptContent: String = ""
    private var systemPromptTag: String = ""
    private var webSearchEnabled: Boolean = false
    private var selectedModelName: String = ""
    private var currentAttachmentName: String? = null
    private var currentConversationId: Long = 0L
    private var currentAssistantMessageId: Long = 0L
    private var isStreaming: Boolean = false
    private var accumulatedContent: String = ""
    private var streamingJob: Job? = null
    private var lastUIUpdateTime: Long = 0L

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

        // Markwon
        val markwon = Markwon.builder(this)
            .usePlugin(CorePlugin.create())
            .usePlugin(TablePlugin.create(this))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .build()

        // RecyclerView
        chatAdapter = ChatAdapter(markwon, this)
        rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = chatAdapter
        }

        // Build initial items
        val initialItems = mutableListOf<ChatItem>()
        initialItems.add(ChatItemBuilder.buildInitialTimestamp())
        if (systemPromptContent.isNotBlank()) {
            initialItems.add(
                ChatItem.SystemPrompt(
                    content = systemPromptContent,
                    tag = systemPromptTag
                )
            )
        }
        chatAdapter.submitList(initialItems)

        // Bottom toolbar actions
        btnNewChat.setOnClickListener { onNewChat() }
        btnSelectImage.setOnClickListener { imagePickerLauncher.launch("image/*") }
        btnSelectFile.setOnClickListener { filePickerLauncher.launch("*/*") }
        btnSelectModel.setOnClickListener { showModelSelectorDialog() }
        btnWebSearch.setOnClickListener { toggleWebSearch() }
        btnSend.setOnClickListener { onSend() }

        updateWebSearchIcon()
    }

    // region ChatAdapterListener

    override fun onSystemPromptToggle(position: Int) {
        val items = chatAdapter.currentList.toMutableList()
        val item = items[position] as? ChatItem.SystemPrompt ?: return
        items[position] = item.copy(isExpanded = !item.isExpanded)
        chatAdapter.submitList(items)
    }

    override fun onContentLongPress(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_content", content))
        Toast.makeText(this, R.string.chat_copy_success, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region Send

    private fun onSend() {
        val text = etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty() || isStreaming) return

        etInput.text?.clear()

        val config = ModelConfigManager.getDefault()
        if (config == null) {
            showErrorMessage(getString(R.string.no_model_available))
            return
        }

        val items = chatAdapter.currentList.toMutableList()
        val now = System.currentTimeMillis()

        // Insert timestamp if needed
        val timestamp = ChatItemBuilder.buildTimestampIfNeeded(items, now)
        if (timestamp != null) {
            items.add(timestamp)
        }

        // Add user message + streaming placeholder in one submit
        items.add(
            ChatItem.UserMessage(
                id = "msg_$now",
                content = text,
                attachmentName = currentAttachmentName
            )
        )
        items.add(ChatItem.StreamingMessage(isThinking = true))
        currentAttachmentName = null
        chatAdapter.submitList(items)
        scrollToBottom()

        // Build message history from local list (not adapter.currentList)
        val userMessages = items.filterIsInstance<ChatItem.UserMessage>()
        val assistantMessages = items.filterIsInstance<ChatItem.AssistantMessage>()
        val history = mutableListOf<MessageContext>()
        val pairs = minOf(userMessages.size, assistantMessages.size)
        for (i in 0 until pairs) {
            history.add(MessageContext("user", userMessages[i].content))
            history.add(MessageContext("assistant", assistantMessages[i].content))
        }

        isStreaming = true
        accumulatedContent = ""
        lastUIUpdateTime = 0L

        streamingJob = lifecycleScope.launch {
            try {
                val result = chatRepository.sendMessage(
                    conversationId = currentConversationId,
                    userMessage = text,
                    history = history,
                    config = config,
                    systemPrompt = systemPromptContent.ifBlank { null }
                )
                currentConversationId = result.conversationId
                currentAssistantMessageId = result.assistantMessageId

                result.stream.collect { event ->
                    when (event) {
                        is StreamEvent.ContentDelta -> {
                            accumulatedContent += event.text
                            val now = System.currentTimeMillis()
                            if (now - lastUIUpdateTime >= 50) {
                                lastUIUpdateTime = now
                                updateStreamingUI(accumulatedContent)
                            }
                            if (accumulatedContent.length % 500 < event.text.length) {
                                chatRepository.updateStreamingContent(
                                    currentAssistantMessageId, accumulatedContent
                                )
                            }
                        }
                        is StreamEvent.StreamEnd -> {
                            updateStreamingUI(accumulatedContent)
                            finishStreaming()
                        }
                        is StreamEvent.Error -> {
                            showErrorMessage(event.message)
                            finishStreaming()
                        }
                    }
                }
            } catch (e: Exception) {
                showErrorMessage("Request failed: ${e.message}")
                finishStreaming()
            }
        }
    }

    private fun updateStreamingUI(content: String) {
        val holder = chatAdapter.findStreamingViewHolder(rvMessages)
        if (holder != null) {
            holder.updateStreamingContent(content)
            if (holder.isExpanded) {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    rvMessages.smoothScrollToPosition(pos)
                }
            }
        }
    }

    private suspend fun finishStreaming() {
        chatRepository.finishMessage(currentAssistantMessageId, accumulatedContent)

        // Update ViewHolder directly one last time
        updateStreamingUI(accumulatedContent)

        val items = chatAdapter.currentList.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items[idx] = ChatItem.AssistantMessage(
                id = "msg_${System.currentTimeMillis()}",
                content = accumulatedContent,
                modelName = selectedModelName.ifEmpty { null }
            )
            chatAdapter.submitList(items)
            scrollToBottom()
        }

        isStreaming = false
        accumulatedContent = ""
        streamingJob = null
    }

    private fun showErrorMessage(message: String) {
        val items = chatAdapter.currentList.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items.removeAt(idx)
        }
        items.add(
            ChatItem.AssistantMessage(
                id = "error_${System.currentTimeMillis()}",
                content = "**Error:** $message",
                modelName = null
            )
        )
        chatAdapter.submitList(items)
        scrollToBottom()
    }

    // endregion

    // region Scroll

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            rvMessages.smoothScrollToPosition(itemCount - 1)
        }
    }

    private fun shouldAutoScroll(): Boolean {
        val layoutManager = rvMessages.layoutManager as LinearLayoutManager
        val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
        val itemCount = layoutManager.itemCount
        return lastVisible >= itemCount - 2
    }

    // endregion

    // region New Chat

    private fun onNewChat() {
        if (chatAdapter.currentList.none { it is ChatItem.UserMessage || it is ChatItem.AssistantMessage }) {
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
        currentAttachmentName = uri.lastPathSegment ?: "image"
    }

    private fun onFilePicked(uri: Uri) {
        Toast.makeText(this, "文件已选择: $uri", Toast.LENGTH_SHORT).show()
        currentAttachmentName = uri.lastPathSegment ?: "file"
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

        for (i in 0 until adapter.groupCount) {
            listView.expandGroup(i)
        }

        listView.setOnChildClickListener { _, _, groupPos, childPos, _ ->
            val child = childList[groupPos][childPos]
            val configId = child["modelConfigId"] ?: return@setOnChildClickListener true
            val modelName = child["modelName"] ?: return@setOnChildClickListener true

            selectedModelName = modelName

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
