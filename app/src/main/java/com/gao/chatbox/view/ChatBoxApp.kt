package com.gao.chatbox.view

import android.app.Application
import com.tencent.mmkv.MMKV

class ChatBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
    }
}
