package com.gao.chatbox.view.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvSystemPrompt = findViewById<TextView>(R.id.tv_system_prompt)

        val tag = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_TAG) ?: ""
        val content = intent.getStringExtra(EXTRA_SYSTEM_PROMPT_CONTENT) ?: ""

        toolbar.title = tag
        toolbar.setNavigationOnClickListener { finish() }

        tvSystemPrompt.text = content
    }
}
