package com.gao.chatbox.view

import android.app.Application
import com.gao.chatbox.view.di.AppComponent
import com.gao.chatbox.view.di.DaggerAppComponent
import com.tencent.mmkv.MMKV

class ChatBoxApp : Application() {

    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        appComponent = DaggerAppComponent.factory().create(this)
    }
}
