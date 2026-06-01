package com.gao.chatbox.view.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.SimpleExpandableListAdapter
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
import com.gao.chatbox.view.databinding.ActivityChatBinding
import com.gao.chatbox.view.util.ModelConfigManager
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

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }
    private val chatRepository: ChatRepository by lazy { ChatRepository.getInstance(this) }
    private var systemPromptContent: String = ""
    private var systemPromptTag: String = ""
    private var webSearchEnabled: Boolean = false
    private var selectedModelName: String = ""
    private var currentAttachmentName: String? = null
    private var currentImageUri: String? = null
    private var currentImageBase64: String? = null
    private var currentMediaType: String? = null
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
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ModelConfigManager.init()

        systemPromptTag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        systemPromptContent = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""

        // Init web search from global setting
        webSearchEnabled = mmkv.decodeBool(KEY_WEB_SEARCH, false)

        // Init default model
        val defaultConfig = ModelConfigManager.getDefault()
        selectedModelName = defaultConfig?.defaultModel?.ifEmpty { defaultConfig.models.firstOrNull() } ?: ""
        binding.tvSelectedModel.text = selectedModelName.ifEmpty { getString(R.string.btn_select_model) }

        // Toolbar
        binding.toolbar.title = systemPromptTag
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
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
        binding.rvMessages.apply {
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
        binding.btnNewChat.setOnClickListener { onNewChat() }
        binding.btnSelectImage.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.btnSelectFile.setOnClickListener { filePickerLauncher.launch("*/*") }
        binding.btnSelectModel.setOnClickListener { showModelSelectorDialog() }
        binding.btnWebSearch.setOnClickListener { toggleWebSearch() }
        binding.btnSend.setOnClickListener { onSend() }

        updateWebSearchIcon()
    }

    // region ChatAdapterListener

    override fun onSystemPromptToggle(position: Int) {
        val items = chatAdapter.items.toMutableList()
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
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty() || isStreaming) return

        binding.etInput.text?.clear()

        // 关闭键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)

        val config = ModelConfigManager.getDefault()
        if (config == null) {
            showErrorMessage(getString(R.string.no_model_available))
            return
        }

        val items = chatAdapter.items.toMutableList()
        val now = System.currentTimeMillis()

        // Insert timestamp if needed
        val timestamp = ChatItemBuilder.buildTimestampIfNeeded(items, now)
        if (timestamp != null) {
            items.add(timestamp)
        }

        // Add user message + streaming placeholder in one submit
        val imageUri = currentImageUri
        val imageBase64 = currentImageBase64
        val mediaType = currentMediaType
        items.add(
            ChatItem.UserMessage(
                id = "msg_$now",
                content = text,
                attachmentName = currentAttachmentName,
                imageUri = imageUri
            )
        )
        items.add(ChatItem.StreamingMessage(isThinking = true))
        currentAttachmentName = null
        currentImageUri = null
        currentImageBase64 = null
        currentMediaType = null
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
                    systemPrompt = systemPromptContent.ifBlank { null },
                    imageBase64 = imageBase64,
                    mediaType = mediaType
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
        val holder = chatAdapter.findStreamingViewHolder(binding.rvMessages)
        if (holder != null) {
            holder.updateStreamingContent(content)
            if (shouldAutoScroll()) {
                binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private suspend fun finishStreaming() {
        chatRepository.finishMessage(currentAssistantMessageId, accumulatedContent)

        // Update ViewHolder directly one last time
        updateStreamingUI(accumulatedContent)

        val scrollNeeded = shouldAutoScroll()
        val items = chatAdapter.items.toMutableList()
        val idx = items.indexOfFirst { it is ChatItem.StreamingMessage }
        if (idx >= 0) {
            items[idx] = ChatItem.AssistantMessage(
                id = "msg_${System.currentTimeMillis()}",
                content = accumulatedContent,
                modelName = selectedModelName.ifEmpty { null }
            )
            chatAdapter.submitList(items)
            if (scrollNeeded) {
                binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }

        isStreaming = false
        accumulatedContent = ""
        streamingJob = null
    }

    private fun showErrorMessage(message: String) {
        val items = chatAdapter.items.toMutableList()
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
            binding.rvMessages.scrollToPosition(itemCount - 1)
        }
    }

    private fun shouldAutoScroll(): Boolean {
        val layoutManager = binding.rvMessages.layoutManager as LinearLayoutManager
        val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
        val itemCount = layoutManager.itemCount
        return lastVisible >= itemCount - 2
    }

    // endregion

    // region New Chat

    private fun onNewChat() {
        if (chatAdapter.items.none { it is ChatItem.UserMessage || it is ChatItem.AssistantMessage }) {
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
        currentImageUri = uri.toString()
        currentAttachmentName = uri.lastPathSegment ?: "image"
        currentMediaType = contentResolver.getType(uri) ?: "image/jpeg"

        try {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            // 压缩到最大 1024px
            val maxDim = 1024
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
            val scaledBitmap = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
            currentImageBase64 = android.util.Base64.encodeToString(
                outputStream.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            Toast.makeText(this, "图片已选择", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "图片读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            currentImageUri = null
            currentImageBase64 = null
            currentMediaType = null
        }
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
        binding.btnWebSearch.setColorFilter(color)
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

            binding.tvSelectedModel.text = modelName
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
