package com.example.unicitywallet.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ChatConversation(
    @PrimaryKey val conversationId: String, // agent's unicity tag
    val agentTag: String,
    val agentPublicKey: String?,
    val lastMessageTime: Long,
    val lastMessageText: String?,
    val unreadCount: Int = 0,
    val isApproved: Boolean = false, // For handshake/approval
    val isAvailable: Boolean = true, // Agent availability status
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversation::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class ChatMessage(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: MessageStatus,
    val type: MessageType = MessageType.TEXT,
    val signature: String? = null
)

enum class MessageStatus {
    PENDING,    // Message waiting to be sent
    SENT,       // Message sent to peer
    DELIVERED,  // Message received by peer
    READ,       // Message read by peer
    FAILED      // Message failed to send
}

enum class MessageType {
    TEXT,
    LOCATION,
    MEETING_REQUEST,
    TRANSACTION_CONFIRM,
    HANDSHAKE_REQUEST,
    HANDSHAKE_ACCEPT,
    HANDSHAKE_REJECT,
    AVAILABILITY_UPDATE,
    IDENTIFICATION,  // Used to identify who connected
    IDENTIFY  // Alias for IDENTIFICATION
}

enum class HandshakeStatus {
    NONE,
    SENT,
    RECEIVED,
    APPROVED,
    REJECTED
}