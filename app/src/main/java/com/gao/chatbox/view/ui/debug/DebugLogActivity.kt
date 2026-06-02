package com.gao.chatbox.view.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gao.chatbox.view.R
import com.gao.chatbox.view.databinding.ActivityDebugLogBinding
import com.gao.chatbox.view.util.DebugLogManager
import com.google.gson.GsonBuilder

class DebugLogActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        fun start(context: Context, conversationId: Long) {
            val intent = Intent(context, DebugLogActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityDebugLogBinding
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.inflateMenu(R.menu.menu_debug_log)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_copy -> {
                    copyLogToClipboard()
                    true
                }
                R.id.action_delete -> {
                    deleteLog()
                    true
                }
                else -> false
            }
        }

        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, 0L)
        if (conversationId <= 0L) {
            showEmpty()
            return
        }

        loadLog(conversationId)
    }

    private fun loadLog(conversationId: Long) {
        val entries = DebugLogManager.readLogFileRaw(this, conversationId)
        if (entries.isEmpty()) {
            showEmpty()
            return
        }

        binding.tvEmpty.visibility = View.GONE
        binding.scrollView.visibility = View.VISIBLE

        val formatted = buildString {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    appendLine()
                    appendLine("─".repeat(80))
                    appendLine()
                }
                appendLine("[${entry.timestamp}] ${entry.type}")
                appendLine("URL: ${entry.url}")
                if (entry.isError) {
                    appendLine("⚠ ERROR")
                }
                appendLine()
                if (entry.requestBody != null) {
                    appendLine("Request:")
                    appendLine(formatJson(entry.requestBody))
                    appendLine()
                }
                if (entry.responseBody != null) {
                    appendLine("Response:")
                    appendLine(formatJson(entry.responseBody))
                }
            }
        }

        binding.tvLogContent.text = formatted
    }

    private fun formatJson(raw: String): String {
        return try {
            val element = com.google.gson.JsonParser.parseString(raw)
            gson.toJson(element)
        } catch (e: Exception) {
            raw
        }
    }

    private fun showEmpty() {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.scrollView.visibility = View.GONE
    }

    private fun copyLogToClipboard() {
        val text = binding.tvLogContent.text?.toString() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", text))
        Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun deleteLog() {
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, 0L)
        if (conversationId <= 0L) return
        DebugLogManager.deleteLogFile(this, conversationId)
        Toast.makeText(this, R.string.debug_log_deleted, Toast.LENGTH_SHORT).show()
        finish()
    }
}
