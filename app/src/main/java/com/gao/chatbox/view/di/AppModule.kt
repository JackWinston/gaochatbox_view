package com.gao.chatbox.view.di

import android.content.Context
import androidx.room.Room
import com.gao.chatbox.view.data.local.db.AppDatabase
import com.gao.chatbox.view.data.local.db.dao.ConversationDao
import com.gao.chatbox.view.data.local.db.dao.MessageDao
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object AppModule {

    @Provides
    @Singleton
    fun provideMmkv(): MMKV = MMKV.defaultMMKV()

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
}
