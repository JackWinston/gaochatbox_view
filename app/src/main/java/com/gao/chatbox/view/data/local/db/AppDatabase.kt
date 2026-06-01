package com.gao.chatbox.view.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gao.chatbox.view.data.local.db.dao.ConversationDao
import com.gao.chatbox.view.data.local.db.dao.MessageDao
import com.gao.chatbox.view.data.local.db.entity.ConversationEntity
import com.gao.chatbox.view.data.local.db.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chatbox.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
