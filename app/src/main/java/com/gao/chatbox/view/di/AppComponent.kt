package com.gao.chatbox.view.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.gao.chatbox.view.ui.chat.ChatViewModel
import com.gao.chatbox.view.ui.home.history.HistoryViewModel
import com.gao.chatbox.view.ui.home.quickstart.QuickStartViewModel
import com.gao.chatbox.view.ui.home.settings.SettingsViewModel
import com.gao.chatbox.view.util.ThemeManager
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance context: Context): AppComponent
    }

    fun chatViewModelFactory(): ChatViewModel.Factory
    fun quickStartViewModelFactory(): QuickStartViewModel.Factory
    fun historyViewModelFactory(): HistoryViewModel.Factory
    fun settingsViewModelFactory(): SettingsViewModel.Factory

    fun provideDataStore(): DataStore<Preferences>
    fun provideThemeManager(): ThemeManager
}
