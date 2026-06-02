package com.gao.chatbox.view.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.gao.chatbox.view.ChatBoxApp
import com.gao.chatbox.view.R
import com.gao.chatbox.view.databinding.ActivityChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.launch

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
    private val viewModel: ChatViewModel by viewModels {
        (application as ChatBoxApp).appComponent.chatViewModelFactory()
    }

    private var dialog: AlertDialog? = null

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
        }

        // Bottom toolbar actions
        binding.btnNewChat.setOnClickListener { onNewChat() }
        binding.btnSelectImage.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.btnSelectFile.setOnClickListener { filePickerLauncher.launch("*/*") }
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

    override fun onResume() {
        super.onResume()
        chatAdapter.refreshSettings()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.chatItems.collect { items ->
                        chatAdapter.submitList(items)
                        scrollToBottom()
                    }
                }
                launch {
                    viewModel.isStreaming.collect { streaming ->
                        // UI can react to streaming state if needed
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
            }
        }
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
        if (text.isEmpty() || viewModel.isStreaming.value) return

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
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
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

            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            Toast.makeText(this, "图片已选择", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "图片读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFilePicked(uri: Uri) {
        Toast.makeText(this, "文件已选择: $uri", Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region Model Selector

    private fun showModelSelectorDialog() {
        val configs = viewModel.getModelConfigs()
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
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion
}
