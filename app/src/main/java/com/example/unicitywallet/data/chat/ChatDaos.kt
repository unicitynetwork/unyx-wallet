package com.example.unicitywallet.data.chat

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun getAllConversations(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    suspend fun getAllConversationsList(): List<ChatConversation>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    suspend fun getConversation(conversationId: String): ChatConversation?

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    fun getConversationFlow(conversationId: String): Flow<ChatConversation?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ChatConversation)

    @Update
    suspend fun updateConversation(conversation: ChatConversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun markAsRead(conversationId: String)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE conversationId = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET isAvailable = :isAvailable WHERE conversationId = :conversationId")
    suspend fun updateAvailability(conversationId: String, isAvailable: Boolean)

    @Query("UPDATE conversations SET isApproved = :isApproved WHERE conversationId = :conversationId")
    suspend fun updateApprovalStatus(conversationId: String, isApproved: Boolean)

    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun getTotalUnreadCount(): Flow<Int?>

    @Delete
    suspend fun deleteConversation(conversation: ChatConversation)

    @Query("UPDATE conversations SET lastMessageTime = :lastMessageTime, lastMessageText = :lastMessageText WHERE conversationId = :conversationId")
    suspend fun updateLastMessage(conversationId: String, lastMessageTime: Long, lastMessageText: String?)

    // Since we don't have a handshakeStatus column, we'll use a combination of isApproved flag
    suspend fun updateHandshakeStatus(conversationId: String, status: HandshakeStatus) {
        when (status) {
            HandshakeStatus.APPROVED -> updateApprovalStatus(conversationId, true)
            HandshakeStatus.REJECTED -> updateApprovalStatus(conversationId, false)
            else -> { /* For SENT, RECEIVED, NONE we don't update approval status */ }
        }
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationList(conversationId: String): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND status = :status")
    suspend fun getMessagesByStatus(conversationId: String, status: MessageStatus): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE status = :status")
    suspend fun getAllMessagesByStatus(status: MessageStatus): List<ChatMessage>

    @Insert
    suspend fun insertMessage(message: ChatMessage)

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Delete
    suspend fun deleteMessage(message: ChatMessage)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessagesForConversation(conversationId: String)
}