package com.gao.chatbox.view.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.InputMethodManager
import android.widget.ExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.SimpleExpandableListAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.databinding.ActivityChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity(), ChatAdapter.ChatAdapterListener {

    companion object {
        private const val EXTRA_SYSTEM_PROMPT_CONTENT = "system_prompt_content"
        private const val EXTRA_SYSTEM_PROMPT_TAG = "system_prompt_tag"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_DISPLAY_TAG = "display_tag"

        fun start(context: Context, content: String, tag: String) {
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_SYSTEM_PROMPT_CONTENT, content)
                putExtra(EXTRA_SYSTEM_PROMPT_TAG, tag)
            }
            context.startActivity(intent)
        }

        fun startExisting(
            context: Context,
            conversationId: Long,
            systemPromptContent: String,
            systemPromptTag: String,
            displayTag: String
        ) {
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_SYSTEM_PROMPT_CONTENT, systemPromptContent)
                putExtra(EXTRA_SYSTEM_PROMPT_TAG, systemPromptTag)
                putExtra(EXTRA_DISPLAY_TAG, displayTag)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private var lastRenderedItems: List<ChatItem> = emptyList()
    private val viewModel: ChatViewModel by viewModels {
        (application as ChatBoxApp).appComponent.chatViewModelFactory()
    }

    private var dialog: AlertDialog? = null

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { onImagePicked(it) }
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { onFilePicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val systemPromptTag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        val systemPromptContent = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""
        val existingConversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, 0L)

        // Toolbar
        val displayTag = intent.getStringExtra(EXTRA_DISPLAY_TAG)
        binding.toolbar.title = displayTag ?: systemPromptTag
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_edit_title -> {
                    showEditTitleDialog()
                    true
                }
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
            itemAnimator = null
        }

        // Bottom toolbar actions
        binding.btnNewChat.setOnClickListener { onNewChat() }
        binding.btnSelectImage.setOnClickListener { imagePickerLauncher.launch(arrayOf("image/*")) }
        binding.btnSelectFile.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        binding.btnSelectModel.setOnClickListener { showModelSelectorDialog() }
        binding.btnWebSearch.setOnClickListener { viewModel.toggleWebSearch() }
        binding.btnSend.setOnClickListener { onSend() }

        // Init ViewModel
        viewModel.initConversation(
            conversationId = existingConversationId,
            systemPromptContent = systemPromptContent,
            systemPromptTag = systemPromptTag
        )

        // Observe ViewModel state
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.chatItems.collect { items ->
                        val keepBottom = shouldMaintainBottomPosition()
                        val streamedItem = items.lastOrNull() as? ChatItem.StreamingMessage
                        val incrementalStreamingUpdate = isStreamingContentOnlyUpdate(lastRenderedItems, items)

                        if (incrementalStreamingUpdate && streamedItem != null) {
                            chatAdapter.updateStreamingMessage(binding.rvMessages, streamedItem)
                        } else {
                            chatAdapter.syncStreamingRenderStates(items)
                            chatAdapter.submitList(items) {
                                if (keepBottom) {
                                    scrollToBottom()
                                }
                            }
                        }
                        lastRenderedItems = items
                    }
                }
                launch {
                    viewModel.isStreaming.collect { streaming ->
                        updateSendingState(streaming)
                    }
                }
                launch {
                    viewModel.pendingResponsePhase.collect { phase ->
                        updatePendingStatus(phase)
                    }
                }
                launch {
                    viewModel.contextCompressionHint.collect { hint ->
                        updateContextCompressionHint(hint)
                    }
                }
                launch {
                    viewModel.selectedModelName.collect { name ->
                        binding.tvSelectedModel.text = name.ifEmpty { getString(R.string.btn_select_model) }
                    }
                }
                launch {
                    viewModel.webSearchEnabled.collect { enabled ->
                        val color = if (enabled) {
                            ContextCompat.getColor(this@ChatActivity, R.color.md_primary)
                        } else {
                            ContextCompat.getColor(this@ChatActivity, R.color.md_outline)
                        }
                        binding.btnWebSearch.setColorFilter(color)
                    }
                }
                launch {
                    viewModel.title.collect { title ->
                        if (title.isNotBlank()) {
                            binding.toolbar.title = title
                        }
                    }
                }
                launch {
                    viewModel.contextUsage.collect { info ->
                        updateContextUsage(info)
                    }
                }
                launch {
                    viewModel.showCharCount.collect {
                        updateAdapterSettings()
                    }
                }
                launch {
                    viewModel.showTokenCount.collect {
                        updateAdapterSettings()
                    }
                }
                launch {
                    viewModel.showModelName.collect {
                        updateAdapterSettings()
                    }
                }
                launch {
                    viewModel.showTimestamp.collect {
                        updateAdapterSettings()
                    }
                }
            }
        }
    }

    private fun updateAdapterSettings() {
        chatAdapter.updateSettings(
            showCharCount = viewModel.showCharCount.value,
            showTokenCount = viewModel.showTokenCount.value,
            showModelName = viewModel.showModelName.value,
            showTimestamp = viewModel.showTimestamp.value
        )
        if (chatAdapter.itemCount > 0) {
            chatAdapter.notifyDataSetChanged()
        }
    }

    private fun updateSendingState(streaming: Boolean) {
        binding.btnSend.isEnabled = !streaming
        binding.btnSend.alpha = if (streaming) 0.38f else 1f
    }

    private fun updatePendingStatus(phase: ChatViewModel.PendingResponsePhase) {
        val keepBottom = shouldMaintainBottomPosition()
        val textRes = when (phase) {
            ChatViewModel.PendingResponsePhase.IDLE -> null
            ChatViewModel.PendingResponsePhase.THINKING -> R.string.chat_thinking
            ChatViewModel.PendingResponsePhase.EXECUTING_TOOLS -> R.string.chat_executing_tools
        }

        if (textRes == null) {
            binding.layoutPendingStatus.visibility = android.view.View.GONE
            binding.tvPendingStatus.text = ""
        } else {
            binding.layoutPendingStatus.visibility = android.view.View.VISIBLE
            binding.tvPendingStatus.setText(textRes)
        }

        if (keepBottom) {
            scrollToBottomAfterLayout()
        }
    }

    private fun updateContextCompressionHint(hint: String?) {
        val keepBottom = shouldMaintainBottomPosition()
        if (hint.isNullOrBlank()) {
            binding.tvContextCompressionHint.visibility = android.view.View.GONE
            binding.tvContextCompressionHint.text = ""
        } else {
            binding.tvContextCompressionHint.visibility = android.view.View.VISIBLE
            binding.tvContextCompressionHint.text = hint
        }

        if (keepBottom) {
            scrollToBottomAfterLayout()
        }
    }

    private fun updateContextUsage(info: ChatViewModel.ContextUsageInfo) {
        val keepBottom = shouldMaintainBottomPosition()
        if (info.contextLimit > 0 && info.currentTokens > 0) {
            binding.layoutContextUsage.visibility = android.view.View.VISIBLE
            binding.progressContext.max = 100
            binding.progressContext.progress = info.percent
            binding.tvContextUsage.text = getString(
                R.string.context_usage_format,
                info.currentTokens.toFloat(),
                info.contextLimit.toFloat(),
                info.percent
            )
        } else {
            binding.layoutContextUsage.visibility = android.view.View.GONE
        }

        if (keepBottom) {
            scrollToBottomAfterLayout()
        }
    }

    private fun isStreamingContentOnlyUpdate(
        previous: List<ChatItem>,
        current: List<ChatItem>
    ): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return false

        var streamingDiffCount = 0
        previous.indices.forEach { index ->
            val oldItem = previous[index]
            val newItem = current[index]

            if (oldItem is ChatItem.StreamingMessage && newItem is ChatItem.StreamingMessage) {
                if (oldItem.id != newItem.id) return false
                val onlyStreamingFieldsChanged =
                    oldItem.isThinking == newItem.isThinking &&
                        oldItem.thinkingStartTime == newItem.thinkingStartTime &&
                        oldItem.content != newItem.content
                if (!onlyStreamingFieldsChanged && oldItem != newItem) return false
                if (oldItem != newItem) streamingDiffCount++
                return@forEach
            }

            if (oldItem != newItem) return false
        }
        return streamingDiffCount == 1
    }

    // region ChatAdapterListener

    override fun onSystemPromptToggle(position: Int) {
        viewModel.toggleSystemPrompt(position)
    }

    override fun onContentLongPress(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_content", content))
        Toast.makeText(this, R.string.chat_copy_success, Toast.LENGTH_SHORT).show()
    }

    override fun onStreamingStop() {
        if (!viewModel.isStreaming.value) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_streaming_title)
            .setMessage(R.string.stop_streaming_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewModel.stopStreaming()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion

    // region Send

    private fun onSend() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if ((text.isEmpty() && !viewModel.hasPendingAttachment.value) || viewModel.isStreaming.value) return

        binding.etInput.text?.clear()

        // 关闭键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)

        viewModel.sendMessage(text)
    }

    // endregion

    // region Scroll

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            binding.rvMessages.scrollToPosition(itemCount - 1)
        }
    }

    private fun scrollToBottomAfterLayout() {
        binding.rvMessages.post {
            scrollToBottom()
        }
    }

    private fun shouldMaintainBottomPosition(threshold: Int = 1): Boolean {
        val layoutManager = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return false
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        if (lastVisiblePosition == RecyclerView.NO_POSITION) {
            return true
        }
        return !binding.rvMessages.canScrollVertically(1) ||
            lastVisiblePosition >= chatAdapter.itemCount - 1 - threshold
    }

    // endregion

    // region New Chat

    private fun onNewChat() {
        val items = viewModel.chatItems.value
        if (items.none { it is ChatItem.UserMessage || it is ChatItem.AssistantMessage }) {
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
        val systemPromptContent = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""
        val systemPromptTag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        start(this, systemPromptContent, systemPromptTag)
    }

    // endregion

    // region Image & File picker

    private fun onImagePicked(uri: Uri) {
        try {
            persistReadPermission(uri)
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("无法解码图片")
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
            val imageBase64 = android.util.Base64.encodeToString(
                outputStream.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            val mediaType = contentResolver.getType(uri) ?: "image/jpeg"
            val displayName = queryDisplayName(uri) ?: "image.jpg"

            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            viewModel.attachImage(
                displayName = displayName,
                imageUri = uri.toString(),
                imageBase64 = imageBase64,
                mediaType = mediaType
            )
            Toast.makeText(this, "图片已加入下一条消息", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "图片读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFilePicked(uri: Uri) {
        val mimeType = contentResolver.getType(uri).orEmpty()
        if (!mimeType.startsWith("text/")) {
            Toast.makeText(this, "目前仅支持文本文件作为附件发送", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                persistReadPermission(uri)
                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (content.isNullOrBlank()) {
                    Toast.makeText(this@ChatActivity, "文件内容为空或无法读取", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val displayName = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "attachment.txt")
                viewModel.attachTextFile(displayName, content)
                Toast.makeText(this@ChatActivity, "文件已加入下一条消息", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "文件读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region Model Selector

    private fun showModelSelectorDialog() {
        lifecycleScope.launch {
            val configs = viewModel.getModelConfigs()
            if (configs.isEmpty()) {
                Toast.makeText(this@ChatActivity, R.string.no_model_available, Toast.LENGTH_SHORT).show()
                return@launch
            }

            showModelSelectorDialog(configs)
        }
    }

    private fun showModelSelectorDialog(configs: List<com.gao.chatbox.view.data.model.ModelConfig>) {

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

            val config = configs.find { it.id == configId }
            if (config != null) {
                viewModel.selectModel(modelName, config)
            }

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

    // endregion

    // region Edit Title

    private fun showEditTitleDialog() {
        val currentTitle = viewModel.title.value
        val editText = android.widget.EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setPadding(64, 32, 64, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_edit_title)
            .setView(editText)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    viewModel.updateTitle(newTitle)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion

    // region Delete

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_conversation_confirm_title)
            .setMessage(R.string.delete_conversation_confirm_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewModel.deleteConversation()
                Toast.makeText(this, R.string.msg_conversation_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }
    }
}
