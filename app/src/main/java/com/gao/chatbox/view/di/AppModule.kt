package com.gao.chatbox.view.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.gao.chatbox.view.data.local.db.AppDatabase
import com.gao.chatbox.view.data.local.db.dao.ConversationDao
import com.gao.chatbox.view.data.local.db.dao.MessageDao
import com.gao.chatbox.view.util.LanguageManager
import com.gao.chatbox.view.util.ThemeManager
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(context: Context): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun provideAppDatabase(context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "chatbox.db"
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()
    }

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideLanguageManager(dataStore: DataStore<Preferences>): LanguageManager {
        return LanguageManager(dataStore)
    }

    @Provides
    @Singleton
    fun provideThemeManager(dataStore: DataStore<Preferences>): ThemeManager {
        return ThemeManager(dataStore)
    }
}
