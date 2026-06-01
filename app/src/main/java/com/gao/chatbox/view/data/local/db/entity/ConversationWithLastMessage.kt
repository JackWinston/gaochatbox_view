package com.gao.chatbox.view.data.local.db.entity

import androidx.room.Embedded

data class ConversationWithLastMessage(
    @Embedded
    val conversation: ConversationEntity,
    val lastMessage: String?,
    val lastMessageTime: Long?
)
