package com.gao.chatbox.view

import android.app.Application
import com.gao.chatbox.view.di.AppComponent
import com.gao.chatbox.view.di.DaggerAppComponent

class ChatBoxApp : Application() {

    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        appComponent = DaggerAppComponent.factory().create(this)
        appComponent.provideThemeManager()
    }
}
