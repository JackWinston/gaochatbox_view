package com.gao.chatbox.view.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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

/**
 * 聊天页面 Activity
 *
 * 核心功能：
 * - 发送用户消息并接收 AI 流式响应
 * - 支持多模态输入（文本 + 图片/文件附件）
 * - 支持 Markdown 渲染（表格、链接、删除线等）
 * - 支持工具调用（网页搜索）的展示和结果回传
 * - 上下文压缩：自动管理历史消息以适应模型上下文窗口
 * - 模型选择：支持切换不同的 AI 模型配置
 * - 流式传输控制：支持停止正在生成的响应
 * - 对话管理：编辑标题、删除对话、新建对话
 *
 * 架构：Activity → ViewModel → ChatRepository → API
 * 实现 ChatAdapter.ChatAdapterListener 接口以处理适配器中的用户交互事件。
 */
class ChatActivity : AppCompatActivity(), ChatAdapter.ChatAdapterListener {

    /** 滚动目标类型枚举 */
    private enum class ScrollTarget {
        NONE,              // 无滚动
        KEEP_BOTTOM,       // 保持在底部（流式传输时）
        TOP_OF_LAST_ITEM   // 将最后一项滚动到顶部（新消息出现时）
    }

    /**
     * 滚动请求数据类
     *
     * @param target 滚动目标类型
     * @param expiresAtMs 请求过期时间（1.5 秒后自动失效）
     * @param attempts 已尝试次数（最多 6 次）
     * @param applyScheduled 是否已安排执行（防止重复 post）
     */
    private data class ScrollRequest(
        val target: ScrollTarget = ScrollTarget.NONE,
        val expiresAtMs: Long = 0L,
        val attempts: Int = 0,
        val applyScheduled: Boolean = false
    ) {
        fun isActive(nowMs: Long): Boolean = target != ScrollTarget.NONE && nowMs <= expiresAtMs
    }

    companion object {
        // Intent Extra 键名
        private const val EXTRA_SYSTEM_PROMPT_CONTENT = "system_prompt_content"
        private const val EXTRA_SYSTEM_PROMPT_TAG = "system_prompt_tag"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_DISPLAY_TAG = "display_tag"

        /**
         * 启动新对话
         *
         * @param context 上下文
         * @param content 系统提示词内容
         * @param tag 系统提示词标签（角色名称）
         */
        fun start(context: Context, content: String, tag: String) {
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_SYSTEM_PROMPT_CONTENT, content)
                putExtra(EXTRA_SYSTEM_PROMPT_TAG, tag)
            }
            context.startActivity(intent)
        }

        /**
         * 启动已有对话
         *
         * @param context 上下文
         * @param conversationId 对话 ID
         * @param systemPromptContent 系统提示词内容
         * @param systemPromptTag 系统提示词标签
         * @param displayTag 显示标签（对话标题）
         */
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

    /** ViewBinding 引用 */
    private lateinit var binding: ActivityChatBinding

    /** 聊天消息列表适配器 */
    private lateinit var chatAdapter: ChatAdapter

    /** 上一次渲染的 Item 列表快照，用于增量更新判断 */
    private var lastRenderedItems: List<ChatItem> = emptyList()

    /** ViewModel，通过 Hilt Factory 注入多个依赖 */
    private val viewModel: ChatViewModel by viewModels {
        (application as ChatBoxApp).appComponent.chatViewModelFactory()
    }

    /** 当前滚动请求状态 */
    private var scrollRequest = ScrollRequest()

    /** 模型选择对话框引用（用于 dismiss） */
    private var dialog: AlertDialog? = null

    /** 图片选择器启动器，选择后触发 onImagePicked 处理 */
    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { onImagePicked(it) }
        }

    /** 文件选择器启动器，选择后触发 onFilePicked 处理 */
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
        binding.rvMessages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyScrollRequestIfNeeded()
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

    /**
     * 观察 ViewModel 中的所有状态变化
     *
     * 并行收集多个 StateFlow：
     * - chatItems: 聊天消息列表 → 增量更新 RecyclerView
     * - isStreaming: 是否正在流式传输 → 控制发送按钮状态
     * - pendingResponsePhase: 等待响应阶段 → 显示"思考中"/"执行工具中"
     * - contextCompressionHint: 上下文压缩提示 → 显示压缩信息
     * - selectedModelName: 当前模型名称 → 更新底部模型选择按钮文本
     * - webSearchEnabled: 网页搜索开关 → 更新搜索按钮颜色
     * - title: 对话标题 → 更新 Toolbar 标题
     * - contextUsage: 上下文使用情况 → 更新进度条
     * - renderSettings: 渲染设置 → 更新适配器的显示配置
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.chatItems.collect { items ->
                        handleChatItemsUpdated(items)
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
                    viewModel.renderSettings.collect { settings ->
                        updateAdapterSettings(settings)
                    }
                }
            }
        }
    }

    private fun updateAdapterSettings(settings: ChatRenderSettings) {
        val settingsChanged = chatAdapter.updateSettings(settings)
        if (settingsChanged && chatAdapter.itemCount > 0) {
            chatAdapter.notifyDataSetChanged()
        }
    }

    private fun updateSendingState(streaming: Boolean) {
        binding.btnSend.isEnabled = !streaming
        binding.btnSend.alpha = if (streaming) 0.38f else 1f
    }

    private fun updatePendingStatus(phase: ChatViewModel.PendingResponsePhase) {
        updateBottomAnchoredChrome {
            val textRes = when (phase) {
                ChatViewModel.PendingResponsePhase.IDLE -> null
                ChatViewModel.PendingResponsePhase.THINKING -> R.string.chat_thinking
                ChatViewModel.PendingResponsePhase.EXECUTING_TOOLS -> R.string.chat_executing_tools
                ChatViewModel.PendingResponsePhase.DIRECT_ANSWER_FALLBACK -> R.string.chat_tool_limit_direct_answer
            }

            if (textRes == null) {
                binding.layoutPendingStatus.visibility = android.view.View.GONE
                binding.tvPendingStatus.text = ""
            } else {
                binding.layoutPendingStatus.visibility = android.view.View.VISIBLE
                binding.tvPendingStatus.setText(textRes)
            }
        }
    }

    private fun updateContextCompressionHint(hint: String?) {
        updateBottomAnchoredChrome {
            if (hint.isNullOrBlank()) {
                binding.tvContextCompressionHint.visibility = android.view.View.GONE
                binding.tvContextCompressionHint.text = ""
            } else {
                binding.tvContextCompressionHint.visibility = android.view.View.VISIBLE
                binding.tvContextCompressionHint.text = hint
            }
        }
    }

    private fun updateContextUsage(info: ChatViewModel.ContextUsageInfo) {
        updateBottomAnchoredChrome {
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
        }
    }

    /**
     * 处理聊天列表更新
     *
     * 优化策略：
     * 1. 增量流式更新：如果只是 StreamingMessage 的 content 变化，
     *    直接更新该 ViewHolder，避免整个列表刷新
     * 2. 全量更新：其他情况使用 submitList + DiffUtil 差异更新
     * 3. 滚动控制：根据当前滚动位置和更新类型决定滚动行为
     */
    private fun handleChatItemsUpdated(items: List<ChatItem>) {
        val streamedItem = items.lastOrNull() as? ChatItem.StreamingMessage
        val incrementalStreamingUpdate = isStreamingContentOnlyUpdate(lastRenderedItems, items)
        val keepBottom = shouldMaintainBottomPosition()

        // 增量更新：仅流式内容变化时，直接操作 ViewHolder
        if (incrementalStreamingUpdate && streamedItem != null) {
            chatAdapter.updateStreamingMessage(binding.rvMessages, streamedItem)
            lastRenderedItems = items
            return
        }

        // 全量更新：计算滚动目标 → 同步流式状态 → 提交新列表
        requestScroll(determineScrollTargetForItems(keepBottom, lastRenderedItems, items))
        chatAdapter.syncStreamingRenderStates(items)
        chatAdapter.submitList(items) {
            applyScrollRequestIfNeeded()
        }
        lastRenderedItems = items
    }

    private fun updateBottomAnchoredChrome(updateUi: () -> Unit) {
        if (shouldMaintainBottomPosition()) {
            ensureKeepBottomScrollRequest()
        }
        updateUi()
        applyScrollRequestIfNeeded()
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

    private fun isStreamingCompletedToAssistant(
        previous: List<ChatItem>,
        current: List<ChatItem>
    ): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return false
        return previous.lastOrNull() is ChatItem.StreamingMessage &&
            current.lastOrNull() is ChatItem.AssistantMessage
    }

    private fun determineScrollTargetForItems(
        keepBottom: Boolean,
        previous: List<ChatItem>,
        current: List<ChatItem>
    ): ScrollTarget {
        if (!keepBottom || current.isEmpty()) {
            return ScrollTarget.NONE
        }
        return if (shouldPositionLastItemAtTop(previous, current)) {
            ScrollTarget.TOP_OF_LAST_ITEM
        } else {
            ScrollTarget.KEEP_BOTTOM
        }
    }

    private fun shouldPositionLastItemAtTop(
        previous: List<ChatItem>,
        current: List<ChatItem>
    ): Boolean {
        if (current.isEmpty() || previous.isEmpty()) return false
        if (isStreamingCompletedToAssistant(previous, current)) return true
        if (current.size == previous.size + 1) {
            return when (current.last()) {
                is ChatItem.UserMessage,
                is ChatItem.StreamingMessage -> true
                else -> false
            }
        }
        return false
    }

    // region ChatAdapterListener - 适配器事件回调

    /** 切换系统提示词的展开/折叠状态 */
    override fun onSystemPromptToggle(position: Int) {
        viewModel.toggleSystemPrompt(position)
    }

    /** 长按消息内容 → 复制到剪贴板 */
    override fun onContentLongPress(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat_content", content))
        Toast.makeText(this, R.string.chat_copy_success, Toast.LENGTH_SHORT).show()
    }

    /** 停止流式传输 → 弹出确认对话框 */
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

    // region Send - 发送消息

    /**
     * 发送消息处理
     *
     * 校验逻辑：
     * - 文本非空 或 有待发送附件
     * - 当前未在流式传输中
     *
     * 处理流程：清空输入框 → 收起键盘 → 调用 ViewModel 发送
     */
    private fun onSend() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if ((text.isEmpty() && !viewModel.hasPendingAttachment.value) || viewModel.isStreaming.value) return

        binding.etInput.text?.clear()

        // 收起软键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)

        viewModel.sendMessage(text)
    }

    // endregion

    // region Scroll - 滚动控制

    /** 平滑滚动到底部 */
    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        val remainingScroll = binding.rvMessages.computeVerticalScrollRange() -
            binding.rvMessages.computeVerticalScrollOffset() -
            binding.rvMessages.computeVerticalScrollExtent()
        if (itemCount > 0 && remainingScroll > 0) {
            binding.rvMessages.scrollBy(0, remainingScroll)
        }
    }

    /** 将最后一项滚动到顶部（offset=0），用于新消息出现时的定位 */
    private fun scrollLastItemToTop() {
        val itemCount = chatAdapter.itemCount
        if (itemCount <= 0) return
        (binding.rvMessages.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(itemCount - 1, 0)
    }

    private fun applyScrollRequestIfNeeded() {
        val request = currentScrollRequest()
        if (request.target == ScrollTarget.NONE || request.applyScheduled) {
            return
        }
        if (request.attempts >= 6 || isScrollTargetSatisfied(request.target)) {
            return
        }
        scrollRequest = request.copy(applyScheduled = true)
        binding.rvMessages.post {
            val activeRequest = currentScrollRequest()
            if (activeRequest.target == ScrollTarget.NONE) {
                scrollRequest = activeRequest.copy(applyScheduled = false)
                return@post
            }
            scrollRequest = activeRequest.copy(
                attempts = activeRequest.attempts + 1,
                applyScheduled = false
            )
            when (activeRequest.target) {
                ScrollTarget.TOP_OF_LAST_ITEM -> scrollLastItemToTop()
                ScrollTarget.KEEP_BOTTOM -> scrollToBottom()
                ScrollTarget.NONE -> Unit
            }
        }
    }

    private fun requestScroll(target: ScrollTarget) {
        scrollRequest = ScrollRequest(
            target = target,
            expiresAtMs = SystemClock.uptimeMillis() + 1500L
        )
    }

    private fun currentScrollRequest(): ScrollRequest {
        val nowMs = SystemClock.uptimeMillis()
        return if (scrollRequest.isActive(nowMs)) {
            scrollRequest
        } else {
            ScrollRequest()
        }
    }

    private fun ensureKeepBottomScrollRequest() {
        if (currentScrollRequest().target == ScrollTarget.NONE) {
            requestScroll(ScrollTarget.KEEP_BOTTOM)
        }
    }

    private fun isScrollTargetSatisfied(target: ScrollTarget): Boolean {
        return when (target) {
            ScrollTarget.NONE -> true
            ScrollTarget.KEEP_BOTTOM -> !binding.rvMessages.canScrollVertically(1)
            ScrollTarget.TOP_OF_LAST_ITEM -> {
                val layoutManager = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return false
                val targetPosition = chatAdapter.itemCount - 1
                if (targetPosition < 0) return true
                val targetView = layoutManager.findViewByPosition(targetPosition) ?: return false
                layoutManager.findFirstVisibleItemPosition() == targetPosition &&
                    kotlin.math.abs(targetView.top) <= 1
            }
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

    // region New Chat - 新建对话

    /**
     * 新建对话处理
     * 如果当前没有消息，直接重启；否则弹出确认对话框
     */
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

    /** 关闭当前 Activity 并重新启动，开始同一角色的新对话 */
    private fun restartChat() {
        finish()
        val systemPromptContent = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""
        val systemPromptTag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        start(this, systemPromptContent, systemPromptTag)
    }

    // endregion

    // region Image & File picker - 图片和文件选择

    /**
     * 处理选中的图片
     *
     * 流程：
     * 1. 获取持久化读取权限
     * 2. 解码图片并缩放到最大 1024px
     * 3. 压缩为 JPEG 并转为 Base64
     * 4. 通过 ViewModel 挂载到下一条消息
     */
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

    /**
     * 处理选中的文件
     *
     * 当前仅支持文本文件（mimeType 以 text/ 开头）。
     * 文件内容会在发送时作为附件拼接到用户消息中。
     */
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

    // region Model Selector - 模型选择器

    /**
     * 显示模型选择对话框
     * 从 ViewModel 获取所有模型配置，构建可展开的分组列表
     */
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

    /**
     * 构建并显示模型选择对话框
     *
     * 使用 ExpandableListView 展示模型配置分组：
     * - 分组标题：配置名称（默认配置标记"(默认)"）
     * - 子项：该配置下的模型列表（默认模型标记"✓"）
     *
     * 选择模型后更新 ViewModel 的当前模型，并将该配置设为默认。
     */
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

        // 展开所有分组
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

    // region Edit Title - 编辑标题

    /**
     * 显示编辑对话标题对话框
     * 预填充当前标题，确认后通过 ViewModel 更新
     */
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

    // region Delete - 删除对话

    /**
     * 显示删除对话确认框
     * 确认后通过 ViewModel 删除对话并关闭页面
     */
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

    /**
     * 查询 URI 对应的文件显示名称
     * 通过 ContentResolver 查询 OpenableColumns.DISPLAY_NAME
     */
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

    /**
     * 获取 URI 的持久化读取权限
     * 部分 DocumentProvider 不支持持久化权限，捕获 SecurityException 忽略
     */
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
